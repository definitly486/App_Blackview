package com.example.app.shell

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Установка всех *.apk из папки apk на sdcard (/sdcard/apk).
 *
 *  • Shizuku запущен  → тихая установка `pm install -r` (без окон подтверждения);
 *  • Shizuku нет      → штатная установка через PackageInstaller (окно подтверждения на каждое
 *                       приложение); нужен доступ ко всем файлам.
 */
object SdcardApkInstaller {

    private const val TAG = "SdcardApkInstaller"
    private const val CONFIRM_TIMEOUT_MS = 120_000L
    private val counter = AtomicInteger(0)

    data class Summary(
        val total: Int,
        val installed: List<String>,
        val failed: List<String>,
        val error: String? = null
    )

    /** Доступ ко всем файлам (нужен только без Shizuku). */
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    private fun localApkDir(): File? {
        val root = Environment.getExternalStorageDirectory()
        return listOf("apk", "APK").map { File(root, it) }.firstOrNull { it.isDirectory }
    }

    suspend fun installAll(context: Context, onProgress: (String) -> Unit): Summary {
        val app = context.applicationContext
        return if (AdbShell.isRunning() && AdbShell.hasPermission()) {
            installViaShizuku(app, onProgress)
        } else {
            installViaPackageInstaller(app, onProgress)
        }
    }

    // ---------- Shizuku: тихая установка ----------

    private suspend fun installViaShizuku(app: Context, onProgress: (String) -> Unit): Summary {
        val list = AdbShell.exec(
            app,
            "for d in /sdcard/apk /sdcard/APK; do " +
                "if [ -d \"\$d\" ]; then find \"\$d\" -maxdepth 1 -type f -iname '*.apk'; break; fi; done"
        )
        val files = list.output.lines().map { it.trim() }.filter { it.endsWith(".apk", ignoreCase = true) }.sorted()
        if (files.isEmpty()) {
            return Summary(0, emptyList(), emptyList(), "В папке /sdcard/apk нет файлов .apk")
        }

        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        files.forEachIndexed { i, path ->
            val name = path.substringAfterLast('/')
            onProgress("[${i + 1}/${files.size}] $name")
            val r = AdbShell.exec(app, "pm install -r \"$path\"")
            if (r.ok && r.output.contains("Success")) installed += name else {
                Log.w(TAG, "Ошибка установки $name: ${r.output}")
                failed += "$name — ${r.output.lineSequence().lastOrNull { it.isNotBlank() } ?: "ошибка"}"
            }
        }
        return Summary(files.size, installed, failed)
    }

    // ---------- Без Shizuku: PackageInstaller + подтверждение пользователя ----------

    private suspend fun installViaPackageInstaller(app: Context, onProgress: (String) -> Unit): Summary {
        if (!hasAllFilesAccess()) {
            return Summary(0, emptyList(), emptyList(), "NEED_ALL_FILES")
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            return Summary(0, emptyList(), emptyList(), "NEED_INSTALL_PERMISSION")
        }

        val dir = localApkDir()
            ?: return Summary(0, emptyList(), emptyList(), "Папка /sdcard/apk не найдена")
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        if (files.isEmpty()) {
            return Summary(0, emptyList(), emptyList(), "В папке ${dir.path} нет файлов .apk")
        }

        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        files.forEachIndexed { i, file ->
            onProgress("[${i + 1}/${files.size}] ${file.name} — подтвердите установку")
            if (installOne(app, file)) installed += file.name else failed += file.name
        }
        return Summary(files.size, installed, failed)
    }

    private suspend fun installOne(app: Context, apk: File): Boolean {
        val requestId = counter.incrementAndGet()
        val action = "${app.packageName}.SDCARD_INSTALL.$requestId"
        val result = CompletableDeferred<Boolean>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm == null) {
                            result.complete(false)
                        } else {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                app.startActivity(confirm)
                            } catch (e: Exception) {
                                Log.e(TAG, "Не удалось открыть подтверждение", e)
                                result.complete(false)
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> result.complete(true)
                    else -> {
                        Log.w(TAG, "Не установлено: ${apk.name} (status=$status, " +
                            "${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)})")
                        result.complete(false)
                    }
                }
            }
        }

        app.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        return try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)

            withContext(Dispatchers.IO) {
                installer.openSession(sessionId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite(apk.name, 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val pending = PendingIntent.getBroadcast(
                        app,
                        requestId,
                        Intent(action).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pending.intentSender)
                }
            }
            withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { result.await() } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка установки ${apk.name}", e)
            false
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
