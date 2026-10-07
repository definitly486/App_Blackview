package com.example.app.fragments

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class AmneziaVpnFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var configText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var backend: GoBackend?
        get() = sharedBackend
        set(value) { sharedBackend = value }

    private var tunnel: Tunnel?
        get() = sharedTunnel
        set(value) { sharedTunnel = value }

    private var config: Config?
        get() = sharedConfig
        set(value) { sharedConfig = value }

    private var configUri: Uri?
        get() = sharedConfigUri
        set(value) { sharedConfigUri = value }

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                connectInternal()
            } else {
                showStatus("VPN permission was denied", false)
            }
        }

    private val filePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            loadConfig(uri)
        }

    /** No-op handler: Android configs rarely use PreUp/PostUp scripts. */
    private val noopActionHandler = object : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>?) {}
        override fun runPostUp(scripts: MutableCollection<String>?) {}
        override fun runPreDown(scripts: MutableCollection<String>?) {}
        override fun runPostDown(scripts: MutableCollection<String>?) {}
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_amnezia_vpn, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusText = view.findViewById(R.id.amneziaStatus)
        configText = view.findViewById(R.id.amneziaConfig)
        connectButton = view.findViewById(R.id.amneziaConnect)
        disconnectButton = view.findViewById(R.id.amneziaDisconnect)

        view.findViewById<Button>(R.id.amneziaOpen).setOnClickListener {
            filePicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }

        connectButton.setOnClickListener {
            if (config == null) {
                Toast.makeText(requireContext(), "Сначала откройте .conf", Toast.LENGTH_SHORT).show()
            } else {
                requestVpnPermissionAndConnect()
            }
        }

        disconnectButton.setOnClickListener {
            disconnect()
        }

        // Переиспользуем backend, чтобы состояние туннеля не терялось при смене вкладок
        if (backend == null) {
            backend = GoBackend(requireContext().applicationContext, noopActionHandler)
        }

        // Восстанавливаем текст конфигурации, если она уже была загружена ранее
        restoreConfigText()

        // Проверяем реальное состояние подключения при открытии вкладки
        checkConnectionState()
    }

    override fun onResume() {
        super.onResume()
        // При каждом возврате на вкладку обновляем статус
        if (::statusText.isInitialized) {
            checkConnectionState()
        }
    }

    /**
     * Проверяет, активно ли VPN-подключение (через ConnectivityManager и состояние backend),
     * и обновляет UI.
     */
    private fun checkConnectionState() {
        val vpnActive = isVpnActive()
        val backendUp = try {
            val t = tunnel
            val b = backend
            if (t != null && b != null) {
                b.getState(t) == Tunnel.State.UP
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        val connected = vpnActive || backendUp

        if (connected) {
            showStatus("● VPN подключён", true)
        } else {
            showStatus("● VPN отключён", false)
            // Если туннель в backend ещё числится, но VPN уже нет — сбрасываем
            if (tunnel != null && !vpnActive) {
                tunnel = null
            }
        }
    }

    private fun isVpnActive(): Boolean {
        return try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreConfigText() {
        val uri = configUri
        val cfg = config
        if (uri != null && cfg != null) {
            val endpoint = cfg.getPeers().firstOrNull()?.getEndpoint()
                ?.map { it.toString() }?.orElse("не указан") ?: "не указан"
            val address = cfg.getInterface().getAddresses().joinToString()
            val dns = cfg.getInterface().getDnsServers().joinToString()

            configText.text = "Файл: ${uri.lastPathSegment ?: "config.conf"}\n" +
                    "Адрес: $address\n" +
                    "Сервер: $endpoint\n" +
                    "DNS: $dns\n" +
                    "Протокол: AmneziaWG"
        } else {
            configText.text = "Конфигурация не выбрана"
        }
    }

    private fun loadConfig(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)!!.use {
                        it.readBytes().toString(StandardCharsets.UTF_8)
                    }
                }

                val parsed = withContext(Dispatchers.Default) {
                    Config.parse(
                        ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
                    )
                }

                configUri = uri
                config = parsed

                val endpoint = parsed.getPeers().firstOrNull()?.getEndpoint()
                    ?.map { it.toString() }?.orElse("не указан") ?: "не указан"
                val address = parsed.getInterface().getAddresses().joinToString()
                val dns = parsed.getInterface().getDnsServers().joinToString()

                configText.text = "Файл: ${uri.lastPathSegment ?: "config.conf"}\n" +
                        "Адрес: $address\n" +
                        "Сервер: $endpoint\n" +
                        "DNS: $dns\n" +
                        "Протокол: AmneziaWG"

                Toast.makeText(requireContext(), "Конфигурация загружена", Toast.LENGTH_SHORT).show()
                checkConnectionState()
            } catch (e: Exception) {
                config = null
                showStatus("Ошибка конфигурации", false)
                Toast.makeText(
                    requireContext(),
                    "Не удалось прочитать конфиг: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            connectInternal()
        }
    }

    private fun connectInternal() {
        val cfg = config ?: return
        val awg = backend ?: return

        connectButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val newTunnel = object : Tunnel {
                    override fun getName(): String = "amezia-vpn"

                    override fun onStateChange(newState: Tunnel.State) {
                        activity?.runOnUiThread {
                            when (newState) {
                                Tunnel.State.UP -> showStatus("● VPN подключён", true)
                                Tunnel.State.DOWN -> showStatus("● VPN отключён", false)
                            }
                        }
                    }

                    // Required by wgtunnel / com.zaneschepke Tunnel API
                    override fun isIpv4ResolutionPreferred(): Boolean = true

                    override fun isMetered(): Boolean = false
                }

                awg.setState(newTunnel, Tunnel.State.UP, cfg)
                tunnel = newTunnel

                withContext(Dispatchers.Main) {
                    showStatus("● VPN подключается…", true)
                    disconnectButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    showStatus("Ошибка подключения", false)
                    Toast.makeText(
                        requireContext(),
                        "AmneziaWG: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun disconnect() {
        val awg = backend ?: return
        val currentTunnel = tunnel ?: return

        connectButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                awg.setState(currentTunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Ошибка отключения: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                tunnel = null
                withContext(Dispatchers.Main) {
                    connectButton.isEnabled = true
                    disconnectButton.isEnabled = false
                    showStatus("● VPN отключён", false)
                }
            }
        }
    }

    private fun showStatus(text: String, connected: Boolean) {
        if (!::statusText.isInitialized) return
        statusText.text = text
        statusText.alpha = if (connected) 1.0f else 0.75f
        connectButton.isEnabled = !connected && config != null
        disconnectButton.isEnabled = connected || tunnel != null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Не отключаем VPN при уничтожении Fragment.
        // VPN должен продолжать работать, пока пользователь явно не нажмёт «Отключить».
        // Backend и tunnel хранятся в companion object и переживают смену вкладок.
    }

    companion object {
        // Общее состояние между пересозданиями фрагмента (при смене вкладок)
        private var sharedBackend: GoBackend? = null
        private var sharedTunnel: Tunnel? = null
        private var sharedConfig: Config? = null
        private var sharedConfigUri: Uri? = null
    }
}