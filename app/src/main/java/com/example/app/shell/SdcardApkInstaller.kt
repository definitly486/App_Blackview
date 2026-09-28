package com.example.app.shell

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Автоматическая установка APK с накопителей.
 *
 * Ищет папку:
 *
 *   /apk
 *   /APK
 *
 * на:
 *
 *   /storage/emulated/0
 *   /storage/XXXX-XXXX       <- SD / USB
 *   /mnt/media_rw/XXXX-XXXX  <- SD / USB
 *
 * При наличии Shizuku:
 *   - ищет APK через shell;
 *   - устанавливает через pm install -r;
 *   - подтверждение пользователя не требуется.
 *
 * Без Shizuku:
 *   - используется PackageInstaller;
 *   - Android может показать подтверждение установки.
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

    /**
     * Нужно только для режима без Shizuku.
     */
    fun hasAllFilesAccess(): Boolean {
        return Environment.isExternalStorageManager()
    }

    /**
     * Основная функция.
     */
    suspend fun installAll(
        context: Context,
        onProgress: (String) -> Unit
    ): Summary {

        val app = context.applicationContext

        return if (
            AdbShell.isRunning() &&
            AdbShell.hasPermission()
        ) {
            installViaShizuku(app, onProgress)
        } else {
            installViaPackageInstaller(app, onProgress)
        }
    }

    // ============================================================
    // SHIZUKU
    // ============================================================

    /**
     * Ищет папки apk на всех доступных накопителях.
     *
     * Важно:
     *
     * В raw Kotlin string shell-переменная должна писаться
     * как ${'$'}root, иначе Kotlin воспримет $root как свою
     * переменную.
     */
    private suspend fun findApkDirectoriesViaShizuku(
        app: Context
    ): List<String> {

        val command = """
            for root in /storage /mnt/media_rw; do
                if [ -d "${'$'}root" ]; then
                    find "${'$'}root" -maxdepth 3 -type d \( -iname 'apk' \) 2>/dev/null
                fi
            done
        """.trimIndent()

        val result = AdbShell.exec(app, command)

        if (!result.ok && result.output.isBlank()) {
            Log.w(
                TAG,
                "Не удалось получить список каталогов: ${result.output}"
            )
            return emptyList()
        }

        return result.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(
                compareBy<String> {
                    when {
                        it == "/storage/emulated/0/apk" -> 0
                        it == "/storage/emulated/0/APK" -> 0
                        it.startsWith("/storage/") -> 1
                        it.startsWith("/mnt/media_rw/") -> 2
                        else -> 3
                    }
                }.thenBy { it }
            )
            .toList()
    }

    /**
     * Получает список APK из найденных папок.
     */
    private suspend fun findApkFilesViaShizuku(
        app: Context
    ): List<String> {

        val dirs = findApkDirectoriesViaShizuku(app)

        if (dirs.isEmpty()) {
            return emptyList()
        }

        val files = mutableListOf<String>()

        for (dir in dirs) {

            /*
             * Здесь $dir должен быть заменён Kotlin,
             * поэтому обычная интерполяция $dir правильная.
             */
            val command =
                "find ${shellQuote(dir)} -maxdepth 1 -type f -iname '*.apk' 2>/dev/null"

            val result = AdbShell.exec(app, command)

            if (!result.ok && result.output.isBlank()) {
                Log.w(
                    TAG,
                    "Не удалось прочитать каталог: $dir"
                )
                continue
            }

            result.output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter {
                    it.endsWith(
                        ".apk",
                        ignoreCase = true
                    )
                }
                .forEach { path ->
                    files += path
                }
        }

        return files
            .distinct()
            .sortedWith(
                compareBy<String> {
                    it.substringAfterLast('/').lowercase()
                }.thenBy { it }
            )
    }

    /**
     * Тихая установка через Shizuku.
     */
    private suspend fun installViaShizuku(
        app: Context,
        onProgress: (String) -> Unit
    ): Summary {

        onProgress("Поиск APK на подключённых накопителях...")

        val files = findApkFilesViaShizuku(app)

        if (files.isEmpty()) {
            return Summary(
                total = 0,
                installed = emptyList(),
                failed = emptyList(),
                error = "Папка apk с APK не найдена ни на одном подключённом накопителе"
            )
        }

        onProgress(
            "Найдено APK: ${files.size}"
        )

        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        files.forEachIndexed { index, path ->

            val name = path.substringAfterLast('/')

            val storageName = when {
                path.startsWith("/storage/emulated/0/") -> {
                    "внутренняя память"
                }

                path.startsWith("/storage/") -> {
                    "SD/USB"
                }

                path.startsWith("/mnt/media_rw/") -> {
                    "SD/USB"
                }

                else -> {
                    "накопитель"
                }
            }

            onProgress(
                "[${index + 1}/${files.size}] " +
                    "$name — $storageName"
            )

            val quotedPath = shellQuote(path)

            val result = AdbShell.exec(
                app,
                "pm install -r $quotedPath"
            )

            if (
                result.ok &&
                result.output.contains(
                    "Success",
                    ignoreCase = true
                )
            ) {

                installed += name

                Log.i(
                    TAG,
                    "Установлено: $path"
                )

            } else {

                val errorMessage =
                    result.output
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .lastOrNull()
                        ?: "ошибка установки"

                failed += "$name — $errorMessage"

                Log.w(
                    TAG,
                    "Ошибка установки $path: ${result.output}"
                )
            }
        }

        return Summary(
            total = files.size,
            installed = installed,
            failed = failed
        )
    }

    // ============================================================
    // PACKAGE INSTALLER — БЕЗ SHIZUKU
    // ============================================================

    /**
     * Получает корневые каталоги доступных StorageVolume.
     */
    private fun getStorageDirectories(
        app: Context
    ): List<File> {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return listOf(
                Environment.getExternalStorageDirectory()
            )
        }

        val storageManager =
            app.getSystemService(
                Context.STORAGE_SERVICE
            ) as StorageManager

        return storageManager.storageVolumes
            .mapNotNull { volume ->
                runCatching {
                    volume.directory
                }.getOrNull()
            }
            .filter {
                it.exists() && it.isDirectory
            }
            .distinctBy {
                it.absolutePath
            }
    }

    /**
     * Ищет папку apk на доступных StorageVolume.
     */
    private fun findLocalApkDirectories(
        app: Context
    ): List<File> {

        val result = mutableListOf<File>()

        for (storageRoot in getStorageDirectories(app)) {

            val apk = File(
                storageRoot,
                "apk"
            )

            val APK = File(
                storageRoot,
                "APK"
            )

            when {
                apk.isDirectory -> {
                    result += apk
                }

                APK.isDirectory -> {
                    result += APK
                }
            }
        }

        return result.distinctBy {
            it.absolutePath
        }
    }

    /**
     * Установка без Shizuku через PackageInstaller.
     */
    private suspend fun installViaPackageInstaller(
        app: Context,
        onProgress: (String) -> Unit
    ): Summary {

        if (!hasAllFilesAccess()) {
            return Summary(
                total = 0,
                installed = emptyList(),
                failed = emptyList(),
                error = "NEED_ALL_FILES"
            )
        }

        if (
            !app.packageManager.canRequestPackageInstalls()
        ) {
            return Summary(
                total = 0,
                installed = emptyList(),
                failed = emptyList(),
                error = "NEED_INSTALL_PERMISSION"
            )
        }

        val dirs = findLocalApkDirectories(app)

        if (dirs.isEmpty()) {
            return Summary(
                total = 0,
                installed = emptyList(),
                failed = emptyList(),
                error = "Папка apk не найдена ни на одном подключённом накопителе"
            )
        }

        val files = dirs
            .flatMap { dir ->

                dir.listFiles { file ->
                    file.isFile &&
                        file.extension.equals(
                            "apk",
                            ignoreCase = true
                        )
                }.orEmpty().toList()
            }
            .distinctBy {
                it.absolutePath
            }
            .sortedWith(
                compareBy<File> {
                    it.name.lowercase()
                }.thenBy {
                    it.absolutePath
                }
            )

        if (files.isEmpty()) {
            return Summary(
                total = 0,
                installed = emptyList(),
                failed = emptyList(),
                error = "В найденных папках apk нет APK-файлов"
            )
        }

        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()

        files.forEachIndexed { index, file ->

            onProgress(
                "[${index + 1}/${files.size}] " +
                    "${file.name} — подтвердите установку"
            )

            if (installOne(app, file)) {
                installed += file.name
            } else {
                failed += file.name
            }
        }

        return Summary(
            total = files.size,
            installed = installed,
            failed = failed
        )
    }

    // ============================================================
    // PACKAGE INSTALLER — ОДИН APK
    // ============================================================

    private suspend fun installOne(
        app: Context,
        apk: File
    ): Boolean {

        val requestId =
            counter.incrementAndGet()

        val action =
            "${app.packageName}.SDCARD_INSTALL.$requestId"

        val result =
            CompletableDeferred<Boolean>()

        val receiver =
            object : BroadcastReceiver() {

                override fun onReceive(
                    ctx: Context,
                    intent: Intent
                ) {

                    when (
                        val status =
                            intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                -1
                            )
                    ) {

                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {

                            val confirm =
                                if (
                                    Build.VERSION.SDK_INT >=
                                    Build.VERSION_CODES.TIRAMISU
                                ) {

                                    intent.getParcelableExtra(
                                        Intent.EXTRA_INTENT,
                                        Intent::class.java
                                    )

                                } else {

                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(
                                        Intent.EXTRA_INTENT
                                    )
                                }

                            if (confirm == null) {

                                result.complete(false)

                            } else {

                                confirm.addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                )

                                try {

                                    app.startActivity(
                                        confirm
                                    )

                                } catch (e: Exception) {

                                    Log.e(
                                        TAG,
                                        "Не удалось открыть подтверждение",
                                        e
                                    )

                                    result.complete(false)
                                }
                            }
                        }

                        PackageInstaller.STATUS_SUCCESS -> {

                            Log.i(
                                TAG,
                                "PackageInstaller успешно установил ${apk.name}"
                            )

                            result.complete(true)
                        }

                        else -> {

                            Log.w(
                                TAG,
                                "Не установлено: ${apk.name}; " +
                                    "status=$status; " +
                                    "message=${
                                        intent.getStringExtra(
                                            PackageInstaller.EXTRA_STATUS_MESSAGE
                                        )
                                    }"
                            )

                            result.complete(false)
                        }
                    }
                }
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            app.registerReceiver(
                receiver,
                IntentFilter(action),
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            app.registerReceiver(
                receiver,
                IntentFilter(action)
            )
        }

        return try {

            val installer =
                app.packageManager.packageInstaller

            val params =
                PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )

            val sessionId =
                installer.createSession(params)

            installer.openSession(sessionId).use { session ->

                withContext(Dispatchers.IO) {

                    apk.inputStream().use { input ->

                        session
                            .openWrite(
                                apk.name,
                                0,
                                apk.length()
                            )
                            .use { output ->

                                input.copyTo(output)

                                session.fsync(output)
                            }
                    }
                }

                val pendingIntent =
                    PendingIntent.getBroadcast(
                        app,
                        requestId,
                        Intent(action)
                            .setPackage(
                                app.packageName
                            ),
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_MUTABLE
                    )

                session.commit(
                    pendingIntent.intentSender
                )
            }

            withTimeoutOrNull(
                CONFIRM_TIMEOUT_MS
            ) {
                result.await()
            } ?: false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Ошибка установки ${apk.name}",
                e
            )

            false

        } finally {

            runCatching {
                app.unregisterReceiver(
                    receiver
                )
            }
        }
    }

    // ============================================================
    // UTILS
    // ============================================================

    /**
     * Безопасное quoting значения для shell.
     *
     * Например:
     *
     * /storage/1234-5678/apk/My App.apk
     *
     * превращается в:
     *
     * '/storage/1234-5678/apk/My App.apk'
     */
    private fun shellQuote(
        value: String
    ): String {

        return "'" +
            value.replace(
                "'",
                "'\\''"
            ) +
            "'"
    }
}
