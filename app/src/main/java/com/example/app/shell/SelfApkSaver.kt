package com.example.app.shell

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import com.example.app.download.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Сохраняет копию самого приложения (установленный APK) в Download/download-APK.
 *
 * - Есть доступ ко всем файлам (MANAGE_EXTERNAL_STORAGE) → обычная запись через File.
 * - Нет доступа → запись через MediaStore (разрешения не нужны).
 */
object SelfApkSaver {

    sealed class Result {
        data class Success(val location: String, val sizeBytes: Long) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun save(context: Context): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val source = File(app.applicationInfo.sourceDir)
            if (!source.isFile) return@withContext Result.Error("Не найден исходный APK: ${source.path}")

            val fileName = buildFileName(app)

            val location = if (Environment.isExternalStorageManager()) {
                copyViaFile(source, fileName)
            } else {
                copyViaMediaStore(app, source, fileName)
            }
            Result.Success(location, source.length())
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildFileName(context: Context): String {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val base = "App_Blackview" + (versionName?.let { "-$it" } ?: "")
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
    }

    private fun copyViaFile(source: File, fileName: String): String {
        val dir = DownloadHelper.publicApkDir()
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку ${dir.path}")
        val target = File(dir, fileName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    private fun copyViaMediaStore(context: Context, source: File, fileName: String): String {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/${DownloadHelper.APK_FOLDER_NAME}"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // Удаляем свою прежнюю копию, иначе MediaStore сохранит файл как «name (1).apk».
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, "$relativePath/")
        )

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore не создал файл")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Не удалось открыть файл для записи")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "/storage/emulated/0/$relativePath/$fileName"
    }
}
