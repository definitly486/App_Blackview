package com.example.app.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.example.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Выполнение команд «как adb shell» прямо на устройстве, без root.
 * Работает через Shizuku (его можно запустить на самом телефоне по Wireless debugging).
 */
object AdbShell {

    private const val REQUEST_CODE = 4242

    @Volatile
    private var service: IShellService? = null

    /** Shizuku запущен и отвечает. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Приложению выдано разрешение Shizuku. */
    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Показывает системный запрос разрешения Shizuku. */
    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine<Boolean> { cont ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (cont.isActive) cont.resume(false)
        }
    }

    private suspend fun connect(context: Context): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }

        return withTimeout(10_000) {
            suspendCancellableCoroutine<IShellService> { cont ->
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, ShellService::class.java.name)
                )
                    .processNameSuffix("shell")
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG)
                    .version(1)

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        val s = IShellService.Stub.asInterface(binder)
                        service = s
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        service = null
                    }
                }
                Shizuku.bindUserService(args, connection)
            }
        }
    }

    /** Выполняет одну команду от имени shell. */
    suspend fun exec(context: Context, command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val raw = connect(context.applicationContext).exec(command)
            val nl = raw.indexOf('\n')
            if (nl < 0) {
                ShellResult(raw.trim().toIntOrNull() ?: -1, "")
            } else {
                ShellResult(raw.substring(0, nl).trim().toIntOrNull() ?: -1, raw.substring(nl + 1).trim())
            }
        } catch (e: Throwable) {
            ShellResult(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Запустить команду «в фоне» (для обработчиков кнопок, которым не нужен результат). */
    fun execAsync(context: Context, command: String, onDone: ((ShellResult) -> Unit)? = null) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val result = if (!isRunning() || !hasPermission()) {
                ShellResult(-1, "Shizuku не запущен или нет разрешения")
            } else {
                exec(app, command)
            }
            onDone?.invoke(result)
        }
    }
}
