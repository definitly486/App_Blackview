package com.example.app.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Установка произвольного APK, вшитого в assets приложения, через системный установщик.
 * Установку подтверждает пользователь.
 */
object ApkInstaller {

    const val OBTAINIUM_ASSET = "Obtainium-kotlin-armeabi-v7a-release.apk"

    fun installFromAssets(context: Context, assetName: String): ShizukuInstaller.Result {
        val app = context.applicationContext

        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${app.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return ShizukuInstaller.Result.NeedInstallPermission
        }

        return try {
            val target = File(app.cacheDir, assetName)
            app.assets.open(assetName).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            ShizukuInstaller.Result.Started
        } catch (e: Exception) {
            ShizukuInstaller.Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
