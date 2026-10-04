package com.example.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import com.example.app.download.DownloadHelper
import com.example.app.shell.AdbShell
import com.example.app.shell.PackageSessionInstaller
import kotlinx.coroutines.delay
import java.io.File

/**
 * Установка скачанных APK из Download/download-APK.
 *
 *  - Shizuku запущен и разрешён → тихая установка `pm install -r` (без окон).
 *  - Shizuku нет → системный установщик (PackageInstaller), на каждый APK окно «Установить».
 */
object ApkAutoInstaller {

    private const val LOG_TAG = "InstallAutoAPK"

    const val ERROR_NEED_INSTALL_PERMISSION = "NEED_INSTALL_PERMISSION"
    const val ERROR_NEED_ALL_FILES = "NEED_ALL_FILES"

    data class Summary(
        val installed: Int = 0,
        val skipped: Int = 0,
        val failed: List<String> = emptyList(),
        val viaShizuku: Boolean = false,
        val error: String? = null
    )

    val APK_NAMES = listOf(
        "Total_Commander_v.3.50d.apk",
        "k9mail-13.0.apk",
        "Google+Authenticator+7.0.apk",
        "Pluma_.private_fast.browser_1.80_APKPure.apk",
        "com.aurora.store_70.apk",
        "ByeByeDPI-arm64-v8a-release.apk",
        "Telegram+X+0.27.5.1747-arm64-v8a.apk",
        "Core+Music+Player_1.0.apk"
    )

    suspend fun installAutoAPK(context: Context?): Summary {
        if (context == null) {
            Log.e(LOG_TAG, "Context is null → автоустановка отменена")
            return Summary(error = "Нет контекста")
        }
        val app = context.applicationContext

        val viaShizuku = AdbShell.isRunning() && AdbShell.hasPermission()

        if (!viaShizuku && !app.packageManager.canRequestPackageInstalls()) {
            return Summary(error = ERROR_NEED_INSTALL_PERMISSION)
        }

        val apkDir = DownloadHelper.publicApkDir()
        Log.i(LOG_TAG, "Автоустановка из ${apkDir.absolutePath}, Shizuku=$viaShizuku")

        var installed = 0
        var skipped = 0
        val failed = mutableListOf<String>()

        for (name in APK_NAMES) {
            val apk = File(apkDir, name)

            if (!apk.canRead()) {
                if (!Environment.isExternalStorageManager()) {
                    return Summary(installed, skipped, failed, viaShizuku, ERROR_NEED_ALL_FILES)
                }
                Log.w(LOG_TAG, "Файл не найден → пропуск: $name")
                failed += "$name (не скачан)"
                continue
            }

            val packageName = getPackageNameFromApk(app, apk)
            if (packageName == null) {
                Log.e(LOG_TAG, "Не удалось прочитать package name → пропуск: $name")
                failed += "$name (повреждённый APK)"
                continue
            }

            if (isPackageInstalled(app.packageManager, packageName)) {
                Log.i(LOG_TAG, "Уже установлен → пропуск: $name [$packageName]")
                skipped++
                continue
            }

            if (viaShizuku) {
                val result = AdbShell.exec(app, "pm install -r \"${apk.absolutePath}\"")
                if (result.ok) {
                    installed++
                } else {
                    Log.e(LOG_TAG, "Ошибка установки $name (код ${result.exitCode}): ${result.output}")
                    failed += name
                }
                delay(700)
            } else {
                val result = PackageSessionInstaller.install(app, apk)
                if (result.ok) {
                    installed++
                } else {
                    Log.e(LOG_TAG, "Ошибка установки $name: ${result.message}")
                    failed += name
                }
            }
        }

        Log.i(LOG_TAG, "Автоустановка APK завершена.")
        return Summary(installed, skipped, failed, viaShizuku)
    }

    private fun getPackageNameFromApk(context: Context, apkFile: File): String? = try {
        context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?.packageName?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Ошибка чтения package name из ${apkFile.name}", e)
        null
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean = try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Ошибка проверки установки пакета $packageName", e)
        false
    }
}
