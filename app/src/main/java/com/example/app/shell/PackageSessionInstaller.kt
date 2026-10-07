package com.example.app.shell

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Установка APK БЕЗ Shizuku — через системный PackageInstaller.
 * На каждый APK система показывает своё окно подтверждения «Установить».
 * APK ставятся строго по очереди: следующий — после ответа на предыдущий.
 */
object PackageSessionInstaller {

    data class Result(val ok: Boolean, val message: String? = null)

    private const val ACTION_PREFIX = "com.example.app.INSTALL_RESULT."
    private const val USER_TIMEOUT_MS = 5 * 60_000L

    /** Устанавливает один APK или набор split APK из одного XAPK. */
    suspend fun installAll(context: Context, apks: List<File>): Result {
        if (apks.isEmpty()) return Result(false, "Нет APK для установки")
        if (apks.size == 1) return install(context, apks.first())

        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val sessionId = try {
            withContext(Dispatchers.IO) {
                val totalSize = apks.sumOf { it.length() }
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    setSize(totalSize)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                try {
                    installer.openSession(id).use { session ->
                        apks.forEachIndexed { index, apk ->
                            apk.inputStream().use { input ->
                                session.openWrite(apk.name.ifBlank { "split-$index.apk" }, 0, apk.length()).use { out ->
                                    input.copyTo(out)
                                    session.fsync(out)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    runCatching { installer.abandonSession(id) }
                    throw e
                }
                id
            }
        } catch (e: Exception) {
            return Result(false, e.message ?: e.javaClass.simpleName)
        }

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(USER_TIMEOUT_MS) { commitAndWait(app, installer, sessionId) }
                ?: Result(false, "Нет ответа от установщика")
        }
    }

    suspend fun install(context: Context, apk: File): Result {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller

        val sessionId = try {
            withContext(Dispatchers.IO) { createAndWriteSession(installer, apk) }
        } catch (e: Exception) {
            return Result(false, e.message ?: e.javaClass.simpleName)
        }

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(USER_TIMEOUT_MS) { commitAndWait(app, installer, sessionId) }
                ?: Result(false, "Нет ответа от установщика")
        }
    }

    private fun createAndWriteSession(installer: PackageInstaller, apk: File): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    private suspend fun commitAndWait(
        app: Context,
        installer: PackageInstaller,
        sessionId: Int
    ): Result = suspendCancellableCoroutine { cont ->
        val action = ACTION_PREFIX + sessionId

        val receiver = object : BroadcastReceiver() {
            fun done(result: Result) {
                runCatching { app.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(result)
            }

            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm == null) {
                            done(Result(false, "Установщик не вернул окно подтверждения"))
                        } else {
                            try {
                                app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) {
                                done(Result(false, e.message ?: "Не удалось открыть установщик"))
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> done(Result(true))
                    else -> done(Result(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
                }
            }
        }

        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        cont.invokeOnCancellation {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
        }

        val pending = PendingIntent.getBroadcast(
            app,
            sessionId,
            Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            installer.openSession(sessionId).use { it.commit(pending.intentSender) }
        } catch (e: Exception) {
            receiver.done(Result(false, e.message ?: e.javaClass.simpleName))
        }
    }
}
