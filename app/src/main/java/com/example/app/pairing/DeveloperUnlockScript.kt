package com.example.app.pairing

import android.content.Intent
import android.graphics.Rect
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
 *  5. открывает «Для разработчиков», включает «Отладка по USB» и подтверждает диалог
 *
 * Результат проверяется по Settings.Global.DEVELOPMENT_SETTINGS_ENABLED и adb_enabled.
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
        val USB_DEBUG = arrayOf("USB debugging", "Отладка по USB")
        val CONFIRM = arrayOf("OK", "ОК", "Allow", "Разрешить")
        const val ADB_ENABLED = "adb_enabled"
    }

    private fun say(s: String) = PairingAutomation.say(s)
    private fun now() = SystemClock.uptimeMillis()

    private fun AccessibilityNodeInfo.label(): String = (text ?: contentDescription)?.toString() ?: ""
    private fun AccessibilityNodeInfo.pkg(): String = packageName?.toString() ?: ""
    private fun AccessibilityNodeInfo.rid(): String = viewIdResourceName ?: ""

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

    private fun isAdbEnabled(): Boolean = runCatching {
        Settings.Global.getInt(svc.contentResolver, ADB_ENABLED, 0) == 1
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
        } else if (!unlockDeveloperMenu()) {
            backToApp()
            return
        }
        enableUsbDebugging()
        backToApp()
    }

    private suspend fun unlockDeveloperMenu(): Boolean {
        say("1/4 Открываю «О телефоне»")
        if (!openAbout()) { say("   ✗ не удалось открыть настройки"); return false }

        say("2/4 Ищу «Номер сборки»")
        var row = findBuildRow() ?: return false

        say("3/4 Нажимаю на «Номер сборки» $TAPS раз")
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
            say("✓ Меню разработчика включено")
            return true
        }
        say("✗ Не включилось. На экране: ${svc.dumpScreen()}")
        return false
    }

    private fun openDeveloperOptions(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { svc.startActivity(intent) }.isSuccess
    }

    /** Переключатель в одной строке с заголовком [title] (ближайший по вертикали справа от него). */
    private fun switchInRow(title: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = Rect().also { title.getBoundsInScreen(it) }
        val maxDy = svc.resources.displayMetrics.density * 40
        return svc.collect { it.pkg() == settingsPkg && it.isCheckable }
            .map { it to Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { (_, r) -> r.left >= t.left && Math.abs(r.centerY() - t.centerY()) <= maxDy }
            .minByOrNull { (_, r) -> Math.abs(r.centerY() - t.centerY()) }
            ?.first
    }

    /** «Для разработчиков» → «Отладка по USB» → включить → подтвердить «Разрешить отладку по USB?». */
    private suspend fun enableUsbDebugging() {
        if (isAdbEnabled()) {
            say("✓ Отладка по USB уже включена")
            return
        }

        say("4/4 Включаю «Отладка по USB»")
        if (!openDeveloperOptions()) { say("   ✗ не удалось открыть «Для разработчиков»"); return }

        val end = now() + 45_000
        var lastClick = 0L
        var lastScroll = 0L
        var scrolls = 0
        var down = true
        while (now() < end && !isAdbEnabled()) {
            val confirm = svc.first { it.rid().endsWith("button1") && it.eq(*CONFIRM) }
            if (confirm != null) {
                svc.click(confirm)
                delay(600)
                continue
            }

            val title = svc.first { it.pkg() == settingsPkg && it.eq(*USB_DEBUG) }
            if (title != null) {
                if (now() - lastClick > 3_000) {
                    val sw = switchInRow(title)
                    if (sw == null || !sw.isChecked) svc.click(sw ?: title)
                    lastClick = now()
                }
            } else if (now() - lastScroll > 1_200 && scrolls < 20) {
                if (!svc.scroll(settingsPkg, down)) down = !down
                scrolls++
                lastScroll = now()
            }
            delay(400)
        }

        if (isAdbEnabled()) {
            say("✓ Готово: отладка по USB включена")
        } else {
            say("✗ Отладка по USB не включилась. На экране: ${svc.dumpScreen()}")
        }
    }

    private fun backToApp() {
        val ctx = svc.applicationContext
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { svc.startActivity(intent) }
    }
}
