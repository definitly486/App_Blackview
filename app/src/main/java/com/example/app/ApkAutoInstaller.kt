package com.example.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import com.example.app.shell.AdbShell
import kotlinx.coroutines.delay
import java.io.File

/**
 * Тихая установка скачанных APK командой `pm install` с правами shell (через Shizuku, без root).
 */
object ApkAutoInstaller {

    private const val LOG_TAG = "InstallAutoAPK"

    suspend fun installAutoAPK(context: Context?) {
        if (context == null) {
            Log.e(LOG_TAG, "Context is null → автоустановка отменена")
            return
        }
        val appContext = context.applicationContext

        if (!AdbShell.isRunning() || !AdbShell.hasPermission()) {
            Toast.makeText(
                appContext,
                "Shizuku не запущен или нет разрешения. Автоустановка отменена (вкладка «Настройка»).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val packageManager = appContext.packageManager

        val apks = listOf(
            "Total_Commander_v.3.50d.apk",
            "k9mail-13.0.apk",
            "Google+Authenticator+7.0.apk",
            "Pluma_.private_fast.browser_1.80_APKPure.apk",
            "com.aurora.store_70.apk",
            "ByeByeDPI-arm64-v8a-release.apk",
            "Telegram+X+0.27.5.1747-arm64-v8a.apk",
            "Core+Music+Player_1.0.apk"
        )

        val appApkDir = appContext.getExternalFilesDir("APK")?.also { it.mkdirs() } ?: run {
            Log.e(LOG_TAG, "Нет папки APK")
            return
        }

        Log.i(LOG_TAG, "Запуск автоустановки APK из: ${appApkDir.absolutePath}")

        for (apkFileName in apks) {
            val apkFile = File(appApkDir, apkFileName)

            if (!apkFile.exists()) {
                Log.w(LOG_TAG, "Файл не найден → пропуск: $apkFileName")
                continue
            }

            val packageName = getPackageNameFromApk(appContext, apkFile)
            if (packageName == null) {
                Log.e(LOG_TAG, "Не удалось прочитать package name из APK → пропуск: $apkFileName")
                continue
            }

            if (isPackageInstalled(packageManager, packageName)) {
                Log.i(LOG_TAG, "Уже установлен → пропуск: $apkFileName [$packageName]")
                continue
            }

            Log.i(LOG_TAG, "Установка: $apkFileName → $packageName")
            val result = AdbShell.exec(appContext, "pm install -r \"${apkFile.absolutePath}\"")
            if (result.ok) {
                Log.i(LOG_TAG, "Успешно установлен: $apkFileName → $packageName")
            } else {
                Log.e(LOG_TAG, "Ошибка установки $apkFileName (код ${result.exitCode}): ${result.output}")
            }

            delay(700)
        }

        Log.i(LOG_TAG, "Автоустановка APK завершена.")
    }

    private fun getPackageNameFromApk(context: Context, apkFile: File): String? {
        return try {
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?.packageName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Ошибка чтения package name из ${apkFile.name}", e)
            null
        }
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Ошибка проверки установки пакета $packageName", e)
            false
        }
    }
}
