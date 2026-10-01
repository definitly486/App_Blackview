package com.example.app.pairing

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.example.app.MainActivity
import kotlinx.coroutines.delay

/**
 * Сценарий «Разблокировать меню разработчика» — тот же приём, что и у «Сопряжения»
 * (служба доступности):
 *
 *  1. открывает Настройки → «О телефоне»
 *  2. находит строку «Номер сборки» (при необходимости заходит в «Сведения о ПО» и листает)
 *  3. нажимает на неё 7 раз
 *  4. если система просит PIN/пароль/графический ключ — ждёт, пока вы введёте его сами
 *
 * Результат проверяется по Settings.Global.DEVELOPMENT_SETTINGS_ENABLED.
 */
class DeveloperUnlockScript(private val svc: PairingAccessibilityService) {

    private companion object {
        const val TAPS = 7

        val BUILD_NUMBER = arrayOf("Build number", "Номер сборки", "Номер версии")
        // DokeOS (Blackview): «О планшете» → «Все параметры и информация» → «Номер сборки»
        val SOFTWARE_INFO = arrayOf(
            "Все параметры и информация", "All specs", "All parameters",
            "Software information", "Сведения о ПО", "Сведения о программном обеспечении",
            "Информация о ПО", "Информация о программном обеспечении"
        )
    }

    private fun say(s: String) = PairingAutomation.say(s)
    private fun now() = SystemClock.uptimeMillis()

    private fun AccessibilityNodeInfo.label(): String = (text ?: contentDescription)?.toString() ?: ""
    private fun AccessibilityNodeInfo.pkg(): String = packageName?.toString() ?: ""

    private fun AccessibilityNodeInfo.has(vararg v: String): Boolean {
        val t = label().replace('\u00A0', ' ')
        return v.any { t.contains(it, ignoreCase = true) }
    }

    private fun AccessibilityNodeInfo.eq(vararg v: String): Boolean {
        val t = label().trim().replace('\u00A0', ' ')
        return v.any { it.equals(t, ignoreCase = true) }
    }

    private val settingsPkg: String by lazy {
        svc.packageManager
            .resolveActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"
    }

    private fun isEnabled(): Boolean = runCatching {
        Settings.Global.getInt(
            svc.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
    }.getOrDefault(false)

    private fun openAbout(): Boolean {
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { svc.startActivity(intent) }.isSuccess
    }

    private fun buildRow(): AccessibilityNodeInfo? =
        svc.first { it.pkg() == settingsPkg && it.has(*BUILD_NUMBER) }

    /** Ищет «Номер сборки»: на некоторых прошивках он спрятан в «Сведения о ПО» — заходим туда. */
    private suspend fun findBuildRow(): AccessibilityNodeInfo? {
        val end = now() + 30_000
        var i = 0
        var lastSoftware = 0L
        while (now() < end) {
            buildRow()?.let { return it }

            val software = svc.first { it.pkg() == settingsPkg && it.eq(*SOFTWARE_INFO) }
            if (software != null && now() - lastSoftware > 3_000) {
                svc.click(software)
                lastSoftware = now()
            } else if (i % 3 == 2) {
                if (!svc.scroll(settingsPkg, true)) svc.scroll(settingsPkg, false)
            }
            delay(400)
            i++
        }
        say("   ✗ не нашёл «Номер сборки»")
        say("   на экране: ${svc.dumpScreen()}")
        return null
    }

    suspend fun run() {
        say("▶ Разблокировка меню разработчика: старт")
        if (isEnabled()) {
            say("✓ Меню разработчика уже включено")
            backToApp()
            return
        }

        say("1/3 Открываю «О телефоне»")
        if (!openAbout()) { say("   ✗ не удалось открыть настройки"); return }

        say("2/3 Ищу «Номер сборки»")
        var row = findBuildRow() ?: return

        say("3/3 Нажимаю на «Номер сборки» $TAPS раз")
        var taps = 0
        while (taps < TAPS && !isEnabled()) {
            // узел мог устареть после перерисовки — ищем заново
            row = buildRow() ?: row
            if (svc.click(row)) taps++
            delay(300)
        }
        say("   нажатий: $taps")

        if (!isEnabled()) {
            say("   если запросит PIN / пароль / ключ — введите его вручную")
            val end = now() + 90_000
            while (now() < end && !isEnabled()) delay(500)
        }

        if (isEnabled()) {
            say("✓ Готово: меню разработчика включено")
        } else {
            say("✗ Не включилось. На экране: ${svc.dumpScreen()}")
        }
        backToApp()
    }

    private fun backToApp() {
        val ctx = svc.applicationContext
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { svc.startActivity(intent) }
    }
}
