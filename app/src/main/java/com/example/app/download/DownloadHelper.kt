@file:Suppress("SpellCheckingInspection")

package com.example.app.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized download helper.
 *
 * A single DownloadManager receiver handles all active downloads, so concurrent
 * downloads no longer overwrite each other's callback state.
 */
class DownloadHelper(context: Context) {

    private val appContext = context.applicationContext
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private enum class DestinationType { PUBLIC, PUBLIC_APK, APP_DOWNLOADS, APP_APK }

    private data class PendingDownload(
        val targetFile: File,
        val onFinished: (File?) -> Unit
    )

    private val pendingDownloads = ConcurrentHashMap<Long, PendingDownload>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val pending = pendingDownloads.remove(id) ?: return

            val file = if (isSuccessful(id)) pending.targetFile.takeIf(File::exists) else null
            pending.onFinished(file)
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    // region Directories

    private fun appDownloadsDir(): File? =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.also(File::mkdirs)

    private fun appApkDir(): File? =
        appContext.getExternalFilesDir("APK")
            ?.also(File::mkdirs)

    private fun publicDownloadsDir(): File? =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.also(File::mkdirs)

    /** Публичная папка Download/download-APK (видна в файловом менеджере). */
    private fun ensurePublicApkDir(): File = publicApkDir().also(File::mkdirs)

    /** Backwards-compatible API used by existing screens. */
    fun getDownloadFolderapk(): File? = appApkDir()

    companion object {
        const val APK_FOLDER_NAME = "download-APK"

        /** /storage/emulated/0/Download/download-APK */
        fun publicApkDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APK_FOLDER_NAME
        )
    }

    // endregion

    // region Network

    fun isNetworkAvailable(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private fun ensureNetwork(): Boolean {
        if (isNetworkAvailable()) return true
        toast("Нет подключения к интернету (Wi-Fi или мобильная сеть)", long = true)
        return false
    }

    // endregion

    // region Public API

    fun downloadApk(url: String, onComplete: ((File?) -> Unit)? = null) {
        downloadFile(
            url = url,
            destinationDir = appDownloadsDir(),
            onExists = { file, name -> installApk(file, name) },
            onSuccess = { file, name -> installApk(file, name) },
            onComplete = onComplete
        )
    }

    fun downloadApkToApkFolder(url: String, onComplete: ((File?) -> Unit)? = null) {
        downloadFile(
            url = url,
            destinationDir = appApkDir(),
            onComplete = onComplete
        )
    }

    /** Скачивает APK в Download/download-APK (без установки). */
    fun downloadApkToPublicApkFolder(url: String, onComplete: ((File?) -> Unit)? = null) {
        downloadFile(
            url = url,
            destinationDir = ensurePublicApkDir(),
            destinationType = DestinationType.PUBLIC_APK,
            onComplete = onComplete
        )
    }

    fun downloadFileSimple(url: String, onComplete: ((File?) -> Unit)? = null) {
        downloadFile(
            url = url,
            destinationDir = appDownloadsDir(),
            onComplete = onComplete
        )
    }

    fun downloadTool(url: String, @Suppress("UNUSED_PARAMETER") toolName: String, onComplete: ((File?) -> Unit)? = null) {
        downloadFile(
            url = url,
            destinationDir = appDownloadsDir(),
            onComplete = onComplete
        )
    }

    /** Скачивает файл в общедоступную папку Download и сообщает о завершении. */
    fun downloadFileToPublic(url: String, onComplete: ((File?) -> Unit)? = null) {
        val dir = publicDownloadsDir() ?: run {
            toast("Нет доступа к папке загрузок")
            onComplete?.invoke(null)
            return
        }

        val fileName = fileNameFromUrl(url)
        val target = File(dir, fileName)

        if (target.exists()) {
            onComplete?.invoke(target)
            toast("Файл уже существует: $fileName")
            return
        }

        if (!ensureNetwork()) {
            onComplete?.invoke(null)
            return
        }

        enqueue(url, fileName, target, DestinationType.PUBLIC) { file ->
            onComplete?.invoke(file)
        }
        toast("Загрузка начата: $fileName")
    }

    fun downloadToPublic(url: String) {
        val fileName = fileNameFromUrl(url)
        val dir = publicDownloadsDir() ?: run {
            toast("Нет доступа к папке загрузок")
            return
        }

        val target = File(dir, fileName)
        if (target.exists()) {
            toast("Файл уже существует")
            return
        }

        if (!ensureNetwork()) return

        enqueue(url, fileName, target, DestinationType.PUBLIC) {
            toast(if (it != null) "Загрузка завершена" else "Ошибка загрузки")
        }
        toast("Загрузка начата")
    }

    // endregion

    private fun downloadFile(
        url: String,
        destinationDir: File?,
        destinationType: DestinationType? = null,
        onExists: ((File, String) -> Unit)? = null,
        onSuccess: ((File, String) -> Unit)? = null,
        onComplete: ((File?) -> Unit)? = null
    ) {
        if (destinationDir == null) {
            toast("Не удалось получить папку для загрузки")
            onComplete?.invoke(null)
            return
        }

        val fileName = fileNameFromUrl(url)
        val target = File(destinationDir, fileName)

        if (target.exists()) {
            toast("Файл уже существует")
            onExists?.invoke(target, fileName)
            onComplete?.invoke(target)
            return
        }

        if (!ensureNetwork()) {
            onComplete?.invoke(null)
            return
        }

        val type = destinationType ?: if (destinationDir == appApkDir()) {
            DestinationType.APP_APK
        } else {
            DestinationType.APP_DOWNLOADS
        }

        enqueue(url, fileName, target, type) { file ->
            if (file != null) onSuccess?.invoke(file, fileName)
            onComplete?.invoke(file)
        }
        toast("Загрузка начата: $fileName")
    }

    private fun enqueue(
        url: String,
        title: String,
        target: File,
        destinationType: DestinationType,
        onFinished: (File?) -> Unit
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
                )
                setTitle(title)
                setDescription("Загружается…")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                allowScanningByMediaScanner()

                when (destinationType) {
                    DestinationType.PUBLIC -> setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        target.name
                    )
                    DestinationType.PUBLIC_APK -> setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "$APK_FOLDER_NAME/${target.name}"
                    )
                    DestinationType.APP_APK -> setDestinationInExternalFilesDir(
                        appContext,
                        "APK",
                        target.name
                    )
                    DestinationType.APP_DOWNLOADS -> setDestinationInExternalFilesDir(
                        appContext,
                        Environment.DIRECTORY_DOWNLOADS,
                        target.name
                    )
                }
            }

            val id = downloadManager.enqueue(request)
            pendingDownloads[id] = PendingDownload(target, onFinished)
        } catch (e: Exception) {
            toast("Ошибка при старте загрузки: ${e.message}")
            onFinished(null)
        }
    }

    private fun isSuccessful(downloadId: Long): Boolean =
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            cursor.moveToFirst() &&
                cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                ) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false

    // region APK installation

    private fun installApk(file: File, @Suppress("UNUSED_PARAMETER") fileName: String? = null) {
        if (!file.isFile) {
            toast("APK файл не найден")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            toast("Разрешите установку из неизвестных источников")
            return
        }

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )

        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            )
        } catch (_: Exception) {
            toast("Не удалось запустить установщик")
        }
    }

    fun installGate(filename: String) {
        val file = File(appDownloadsDir() ?: return, filename)
        installApk(file)
    }

    fun installApkFromApkFolder(filename: String) {
        val file = File(appApkDir() ?: return, filename)
        installApk(file)
    }

    // endregion

    private fun fileNameFromUrl(url: String): String =
        url.substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
            .ifBlank { "download" }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(
            appContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    fun cleanup() {
        pendingDownloads.clear()
        runCatching { appContext.unregisterReceiver(downloadReceiver) }
    }
}
