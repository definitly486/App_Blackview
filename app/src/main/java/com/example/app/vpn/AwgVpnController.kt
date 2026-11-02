package com.example.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.net.Inet4Address

/**
 * Управляет AmneziaWG-туннелем независимо от жизненного цикла Fragment.
 *
 * Что добавлено по сравнению с «голым» GoBackend.setState():
 *  1. Реальный статус: «подключён» только после успешного handshake (а не сразу после setState).
 *  2. Реакция на смену сети (Wi-Fi <-> мобильная, потеря/возврат сети, смена IPv4):
 *     туннель перезапускается с debounce, чтобы сокеты и endpoint не «застревали» на старой сети.
 *  3. Watchdog: если handshake нет дольше таймаута (с нарастающим backoff) — перезапуск туннеля.
 *  4. Пока сети нет совсем — перезапуски не выполняются (не жжём батарею), статус «Ожидание сети».
 */
object AwgVpnController {

    enum class Phase { DISCONNECTED, CONNECTING, WAITING_NETWORK, CONNECTED }

    data class Status(val phase: Phase, val detail: String = "")

    private const val TAG = "AwgVpnController"

    private const val POLL_INTERVAL_MS = 1_000L

    /** Сколько ждём первый handshake после (пере)запуска; растёт с каждой неудачей до MAX. */
    private const val HANDSHAKE_TIMEOUT_MS = 20_000L
    private const val HANDSHAKE_TIMEOUT_MAX_MS = 60_000L

    /**
     * Handshake старше этого значения считается устаревшим.
     * WireGuard пересогласует ключи примерно каждые 2 минуты, REJECT_AFTER_TIME = 180 с.
     */
    private const val HANDSHAKE_STALE_MS = 200_000L

    /** Склеиваем пачку сетевых событий (onAvailable + onLost при переключении) в один перезапуск. */
    private const val NETWORK_DEBOUNCE_MS = 1_500L

    /** При регистрации callback система сразу «рассказывает» о текущих сетях — их игнорируем. */
    private const val NETWORK_ARM_DELAY_MS = 2_000L

    private const val TUNNEL_NAME = "awg-vpn"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(Status(Phase.DISCONNECTED))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _errors = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val noopActionHandler = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>?) {}
        override fun runPostUp(scripts: MutableCollection<String>?) {}
        override fun runPreDown(scripts: MutableCollection<String>?) {}
        override fun runPostDown(scripts: MutableCollection<String>?) {}
    }

    private var appContext: Context? = null
    private var backend: GoBackend? = null

    @Volatile private var tunnel: Tunnel? = null
    @Volatile private var config: Config? = null
    @Volatile private var desiredUp = false
    @Volatile private var offline = false
    @Volatile private var armed = false

    // Только из корутин под mutex / из watchdog (один поток логики), поэтому без доп. синхронизации.
    @Volatile private var tunnelStartedAt = 0L
    @Volatile private var lastOkAt = 0L
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastStartAttemptAt = 0L

    private var watchdogJob: Job? = null
    private var restartJob: Job? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networks = mutableSetOf<Network>()
    private val linkSignatures = HashMap<Network, String>()

    private enum class StartResult { OK, RETRY, FATAL }

    // ---------------------------------------------------------------- public API

    @Synchronized
    fun init(context: Context) {
        if (backend == null) {
            val app = context.applicationContext
            appContext = app
            backend = GoBackend(app, noopActionHandler)
        }
    }

    /** Запускает туннель. VPN-разрешение (VpnService.prepare) должно быть уже получено. */
    @Synchronized
    fun connect(cfg: Config) {
        if (backend == null || desiredUp) return
        desiredUp = true
        offline = false
        config = cfg
        consecutiveFailures = 0
        publish(Phase.CONNECTING, "запуск")

        scope.launch {
            val result = mutex.withLock { startTunnelLocked() }
            if (result != StartResult.OK) {
                disconnectInternal()
                return@launch
            }
            registerNetworkCallback()
            startWatchdog()
        }
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    // ---------------------------------------------------------------- tunnel lifecycle

    private suspend fun disconnectInternal() {
        desiredUp = false
        watchdogJob?.cancel()
        restartJob?.cancel()
        unregisterNetworkCallback()
        mutex.withLock {
            stopTunnelLocked()
            tunnel = null
        }
        _status.value = Status(Phase.DISCONNECTED)
    }

    private fun newTunnel(): Tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        // UP здесь означает лишь «интерфейс поднят», а не «есть handshake» — статус берём из статистики.
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "tunnel state: $newState")
        }

        override fun isIpv4ResolutionPreferred(): Boolean = true

        override fun isMetered(): Boolean = false
    }

    private fun startTunnelLocked(): StartResult {
        val awg = backend ?: return StartResult.FATAL
        val cfg = config ?: return StartResult.FATAL
        val newTunnel = newTunnel()
        lastStartAttemptAt = SystemClock.elapsedRealtime()
        return try {
            awg.setState(newTunnel, Tunnel.State.UP, cfg)
            tunnel = newTunnel
            tunnelStartedAt = SystemClock.elapsedRealtime()
            StartResult.OK
        } catch (e: Exception) {
            Log.w(TAG, "setState(UP) failed", e)
            _errors.tryEmit("AmneziaWG: ${e.message ?: e.javaClass.simpleName}")
            val notAuthorized = e is BackendException &&
                e.reason == BackendException.Reason.VPN_NOT_AUTHORIZED
            if (notAuthorized) StartResult.FATAL else StartResult.RETRY
        }
    }

    private fun stopTunnelLocked() {
        val awg = backend ?: return
        val t = tunnel ?: return
        try {
            awg.setState(t, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            Log.w(TAG, "setState(DOWN) failed", e)
        }
    }

    private suspend fun restart(reason: String) {
        if (!desiredUp || offline) return
        var fatal = false
        mutex.withLock {
            if (!desiredUp || offline) return
            Log.i(TAG, "restart: $reason")
            publish(Phase.CONNECTING, "переподключение")
            stopTunnelLocked()
            when (startTunnelLocked()) {
                StartResult.OK -> Unit
                StartResult.RETRY -> Unit // watchdog повторит с backoff
                StartResult.FATAL -> fatal = true
            }
        }
        if (fatal) disconnectInternal()
    }

    // ---------------------------------------------------------------- watchdog

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && desiredUp) {
                delay(POLL_INTERVAL_MS)
                if (!desiredUp) break

                if (offline) {
                    publish(Phase.WAITING_NETWORK)
                    continue
                }

                val running = isTunnelRunning()
                val handshakeAt = if (running) lastHandshakeEpochMs() else 0L
                val now = SystemClock.elapsedRealtime()
                val fresh = handshakeAt > 0 &&
                    System.currentTimeMillis() - handshakeAt < HANDSHAKE_STALE_MS

                if (fresh) {
                    consecutiveFailures = 0
                    lastOkAt = now
                    publish(Phase.CONNECTED)
                    continue
                }

                // Окно ожидания отсчитываем от последнего «ок» или от старта туннеля —
                // так после выхода из Doze у туннеля есть время сам пересогласовать ключи.
                val waitedMs = now - maxOf(tunnelStartedAt, lastOkAt)
                publish(Phase.CONNECTING, "ожидание сервера ${waitedMs / 1000} с")

                if (!running) {
                    // Туннель не поднят (ошибка предыдущего запуска или его остановила система):
                    // повторяем, но с растущей паузой, а не раз в секунду.
                    if (now - lastStartAttemptAt >= retryDelayMs()) {
                        consecutiveFailures++
                        restart("tunnel not running")
                    }
                } else if (waitedMs > handshakeTimeoutMs()) {
                    consecutiveFailures++
                    restart("handshake timeout")
                }
            }
        }
    }

    private fun retryDelayMs(): Long =
        minOf(3_000L * maxOf(consecutiveFailures, 1), 30_000L)

    private fun handshakeTimeoutMs(): Long =
        minOf(HANDSHAKE_TIMEOUT_MS * (1 + consecutiveFailures), HANDSHAKE_TIMEOUT_MAX_MS)

    private fun isTunnelRunning(): Boolean = try {
        val t = tunnel
        t != null && backend?.getState(t) == Tunnel.State.UP
    } catch (_: Exception) {
        false
    }

    /** Время последнего handshake (epoch ms) по всем пирам; 0 — handshake ещё не было. */
    private fun lastHandshakeEpochMs(): Long = try {
        val t = tunnel
        val stats = if (t != null) backend?.getStatistics(t) else null
        var latest = 0L
        if (stats != null) {
            for (key in stats.peers()) {
                val h = stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L
                if (h > latest) latest = h
            }
        }
        latest
    } catch (e: Exception) {
        Log.w(TAG, "getStatistics failed", e)
        0L
    }

    // ---------------------------------------------------------------- network monitoring

    private fun registerNetworkCallback() {
        val app = appContext ?: return
        if (networkCallback != null) return

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // NOT_VPN — нас интересуют только «нижележащие» сети, а не наш собственный туннель.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        synchronized(networks) {
            networks.clear()
            linkSignatures.clear()
        }
        armed = false

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasEmpty = synchronized(networks) {
                    val empty = networks.isEmpty()
                    networks.add(network)
                    empty
                }
                if (!armed) return
                if (wasEmpty) offline = false
                scheduleRestart("network available")
            }

            override fun onLost(network: Network) {
                val nowEmpty = synchronized(networks) {
                    networks.remove(network)
                    linkSignatures.remove(network)
                    networks.isEmpty()
                }
                if (!armed) return
                if (nowEmpty) {
                    // Сети нет совсем: перезапускать бессмысленно, ждём onAvailable.
                    offline = true
                    restartJob?.cancel()
                } else {
                    scheduleRestart("network lost")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // Смотрим только на IPv4-адреса: endpoint у нас IPv4, а IPv6 privacy-адреса меняются сами.
                val signature = linkProperties.linkAddresses
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress ?: "" }
                    .sorted()
                    .joinToString(",")
                val changed = synchronized(networks) {
                    val old = linkSignatures.put(network, signature)
                    old != null && old != signature
                }
                if (armed && changed) scheduleRestart("ip changed")
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
            return
        }
        connectivityManager = cm
        networkCallback = callback

        scope.launch {
            delay(NETWORK_ARM_DELAY_MS)
            offline = synchronized(networks) { networks.isEmpty() }
            armed = true
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        armed = false
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun scheduleRestart(reason: String) {
        if (!desiredUp) return
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            restart(reason)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun publish(phase: Phase, detail: String = "") {
        if (!desiredUp) return
        val next = Status(phase, detail)
        if (_status.value != next) _status.value = next
    }
}
