package com.example.app.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Установка Shizuku из APK, вшитого в assets приложения.
 * Установку подтверждает пользователь в системном установщике.
 */
object ShizukuInstaller {

    const val PACKAGE = "moe.shizuku.privileged.api"
    const val ASSET_NAME = "shizuku-v13.6.0.r1086.2650830c-release.apk"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    sealed class Result {
        object Started : Result()
        object NeedInstallPermission : Result()
        data class Error(val message: String) : Result()
    }

    fun install(context: Context): Result {
        val app = context.applicationContext

        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${app.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return Result.NeedInstallPermission
        }

        return try {
            val target = File(app.cacheDir, ASSET_NAME)
            app.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }

            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
            app.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            Result.Started
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
