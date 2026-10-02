package com.example.app.pairing

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.example.app.MainActivity
import kotlinx.coroutines.delay

/**
 * Сценарий «Разблокировать меню разработчика» одной кнопкой:
 *
 *  1. «Настройки» → «О телефоне»
 *  2. находит строку «Номер сборки» (при необходимости листает / заходит в «Сведения о ПО»)
 *  3. нажимает на неё, пока не появится «Вы стали разработчиком»
 *  4. если система просит PIN / пароль / рисунок — ждёт, пока пользователь введёт их сам
 *     (службе доступности нельзя и не нужно трогать экран блокировки)
 *
 *  5. открывает «Для разработчиков» и включает «Отладка по USB» (подтверждает диалог)
 *
 * Готовность определяется по Settings.Global.DEVELOPMENT_SETTINGS_ENABLED и ADB_ENABLED.
 */
class DevOptionsUnlockScript(private val svc: PairingAccessibilityService) {

    private companion object {
        val BUILD_NUMBER = arrayOf("Build number", "Номер сборки", "Номер версии")
        val SOFTWARE_INFO = arrayOf(
            "Software information", "Сведения о ПО", "Информация о ПО",
            "Сведения о программном обеспечении", "Информация о программном обеспечении"
        )
        val DEV_OPTIONS = arrayOf("Developer options", "Параметры разработчика", "Для разработчиков")
        val USB_DEBUG = arrayOf("USB debugging", "Отладка по USB")
        val CONFIRM = arrayOf("OK", "ОК", "Allow", "Разрешить", "Включить", "Enable")
        const val TAPS_NEEDED = 7
        const val PIN_WAIT_MS = 90_000L
    }

    private fun say(s: String) = PairingAutomation.say(s)
    private fun now() = SystemClock.uptimeMillis()

    private fun AccessibilityNodeInfo.label(): String = (text ?: contentDescription)?.toString() ?: ""
    private fun AccessibilityNodeInfo.pkg(): String = packageName?.toString() ?: ""
    private fun AccessibilityNodeInfo.eq(vararg v: String): Boolean {
        val t = label().trim().replace('\u00A0', ' ')
        return v.any { it.equals(t, ignoreCase = true) }
    }

    private val settingsPkg: String by lazy {
        svc.packageManager
            .resolveActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"
    }

    private fun AccessibilityNodeInfo.inSettings() = pkg() == settingsPkg

    private fun devEnabled(): Boolean =
        Settings.Global.getInt(svc.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun usbEnabled(): Boolean =
        Settings.Global.getInt(svc.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun openDevOptions(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { svc.startActivity(intent) }.isSuccess
    }

    /** Переключатель в одной строке с заголовком [title] (ближайший по вертикали справа от названия). */
    private fun rowSwitch(title: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = Rect().also { title.getBoundsInScreen(it) }
        val maxDy = svc.resources.displayMetrics.density * 40
        return svc.collect { it.inSettings() && it.isCheckable }
            .map { it to Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { (_, r) -> r.left >= t.left && Math.abs(r.centerY() - t.centerY()) <= maxDy }
            .minByOrNull { (_, r) -> Math.abs(r.centerY() - t.centerY()) }
            ?.first
    }

    /** Включает «Отладка по USB» на странице «Для разработчиков». */
    private suspend fun enableUsbDebugging(): Boolean {
        if (usbEnabled()) { say("   ✓ Отладка по USB уже включена"); return true }
        if (!openDevOptions()) { say("   ✗ не удалось открыть «Для разработчиков»"); return false }

        val end = now() + 45_000
        var lastClick = 0L
        var lastScroll = 0L
        var scrolls = 0
        var down = false
        while (now() < end) {
            if (usbEnabled()) {
                // подтверждающий диалог мог ещё висеть — закрываем
                delay(300)
                return true
            }

            // Диалог «Разрешить отладку по USB?»
            val confirm = svc.first { it.viewIdResourceName?.endsWith("button1") == true && it.eq(*CONFIRM) }
            if (confirm != null) {
                svc.click(confirm)
                delay(500)
                continue
            }

            if (svc.first { it.inSettings() } == null) { delay(500); continue }

            val title = svc.first { it.inSettings() && it.eq(*USB_DEBUG) }
            if (title != null) {
                val sw = rowSwitch(title)
                if (sw != null && !sw.isChecked && now() - lastClick > 3_000) {
                    svc.click(sw); lastClick = now()
                }
            } else if (now() - lastScroll > 1_200 && scrolls < 14) {
                // строка не видна — листаем только «Настройки»
                if (!svc.scroll(settingsPkg, down)) down = !down
                scrolls++
                lastScroll = now()
            }
            delay(400)
        }
        return usbEnabled()
    }

    private fun openAbout(): Boolean {
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { svc.startActivity(intent) }.isSuccess
    }

    private fun buildRow() = svc.first { it.inSettings() && it.eq(*BUILD_NUMBER) }

    /** Экран подтверждения блокировки: поле ввода пароля/PIN или рисунок в «Настройках». */
    private fun lockScreenVisible(): Boolean =
        svc.first { it.inSettings() && (it.isPassword || it.viewIdResourceName?.contains("lockPattern") == true || it.viewIdResourceName?.contains("password_entry") == true || it.viewIdResourceName?.contains("pinEntry") == true) } != null

    suspend fun run() {
        say("▶ Разблокировка меню разработчика: старт")

        if (devEnabled()) {
            say("✓ Меню разработчика уже включено")
            finishWithUsb()
            return
        }

        say("1/4 Открываю «О телефоне»")
        if (!openAbout()) { say("   ✗ не удалось открыть «О телефоне»"); return }

        // 2. Ищем «Номер сборки»
        say("2/4 Ищу «Номер сборки»")
        val end = now() + 40_000
        var lastSoft = 0L
        var lastScroll = 0L
        var scrolls = 0
        var down = true
        var row: AccessibilityNodeInfo? = null
        while (now() < end) {
            row = buildRow()
            if (row != null) break
            if (svc.first { it.inSettings() } == null) { delay(500); continue }

            // на некоторых прошивках «Номер сборки» спрятан в «Сведения о ПО»
            val soft = svc.first { it.inSettings() && it.eq(*SOFTWARE_INFO) }
            if (soft != null && now() - lastSoft > 3_000) {
                svc.click(soft); lastSoft = now()
            } else if (now() - lastScroll > 1_200 && scrolls < 14) {
                if (!svc.scroll(settingsPkg, down)) down = !down
                scrolls++
                lastScroll = now()
            }
            delay(400)
        }
        if (row == null) {
            say("   ✗ не нашёл «Номер сборки»")
            say("   на экране: ${svc.dumpScreen()}")
            return
        }

        // 3. Нажимаем
        say("3/4 Нажимаю «Номер сборки» ×$TAPS_NEEDED")
        var taps = 0
        var pinNoticed = false
        val pinDeadline = now() + PIN_WAIT_MS + 20_000
        while (!devEnabled() && now() < pinDeadline) {
            if (lockScreenVisible()) {
                if (!pinNoticed) {
                    say("   🔒 Введите PIN / пароль экрана блокировки — жду…")
                    pinNoticed = true
                }
                delay(700)
                continue
            }
            val r = buildRow()
            if (r != null && taps < TAPS_NEEDED + 3) {
                svc.click(r)
                taps++
                delay(260)
            } else {
                // после ввода PIN система возвращает на страницу; даём ей время или ждём строку
                delay(500)
                if (taps >= TAPS_NEEDED + 3 && !pinNoticed) break
            }
        }

        if (devEnabled()) {
            say("   ✓ Вы стали разработчиком")
            delay(600)
            finishWithUsb()
        } else {
            say("   ✗ Не удалось включить меню разработчика")
            say("   на экране: ${svc.dumpScreen()}")
        }
    }

    private suspend fun finishWithUsb() {
        say("4/4 Включаю «Отладка по USB»")
        val ok = enableUsbDebugging()
        say(if (ok) "   ✓ Отладка по USB включена" else "   ✗ Не удалось включить отладку по USB")
        if (!ok) say("   на экране: ${svc.dumpScreen()}")
        backToApp()
        say(if (ok) "✓ Готово: меню разработчика и отладка по USB включены" else "■ Меню разработчика включено, отладка по USB — нет")
    }

    private fun backToApp() {
        val intent = Intent(svc.applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { svc.startActivity(intent) }
    }
}
