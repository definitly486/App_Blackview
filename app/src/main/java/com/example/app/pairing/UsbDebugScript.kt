package com.example.app.pairing

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.example.app.MainActivity
import kotlinx.coroutines.delay

/**
 * Сценарий «Включить / выключить отладку по USB» — через службу доступности (нажатие кнопок):
 *
 *  1. если отладка уже в нужном состоянии — ничего не открывает
 *  2. открывает Настройки → «Для разработчиков» и находит строку «Отладка по USB»
 *  3. нажимает на её переключатель
 *  4. при включении подтверждает системный диалог «Разрешить отладку по USB?»
 *
 * Результат проверяется по Settings.Global.ADB_ENABLED.
 * Меню разработчика должно быть уже включено (кнопка «Разблокировать меню разработчика»).
 *
 * @param enable true — включить отладку, false — выключить.
 */
class UsbDebugScript(
    private val svc: PairingAccessibilityService,
    private val enable: Boolean = true
) {

    private companion object {
        val USB_DEBUG = arrayOf("USB debugging", "Отладка по USB", "Отладка USB")
        // «Отозвать доступ к отладке по USB» и т.п. — не наша строка
        val NOT_USB_ROW = arrayOf("Revoke", "Отозвать", "авторизац", "authorization", "доступ к отладке")
        /**
         * Номер строки «Отладка по USB» в списке «Для разработчиков», считая сверху с 1.
         * Если на другой прошивке/версии строка стоит иначе — поменяйте это число
         * (в логе при нахождении пишется «строка №…»).
         */
        const val USB_ROW_NUMBER = 19

        val DEV_OPTIONS = arrayOf("Для разработчиков", "Параметры разработчика", "Developer options")
        val CONFIRM = arrayOf("OK", "ОК", "Enable", "Включить", "Allow", "Разрешить", "Да", "Yes")
    }

    private fun say(s: String) = PairingAutomation.say(s)
    private fun now() = SystemClock.uptimeMillis()

    private fun AccessibilityNodeInfo.label(): String = (text ?: contentDescription)?.toString() ?: ""
    private fun AccessibilityNodeInfo.pkg(): String = packageName?.toString() ?: ""
    private fun AccessibilityNodeInfo.rid(): String = viewIdResourceName ?: ""

    private fun AccessibilityNodeInfo.eq(vararg v: String): Boolean {
        val t = label().trim().replace('\u00A0', ' ')
        return v.any { it.equals(t, ignoreCase = true) }
    }

    private val settingsPkg: String by lazy {
        svc.packageManager
            .resolveActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"
    }

    private fun devEnabled(): Boolean = runCatching {
        Settings.Global.getInt(svc.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

    private fun adbEnabled(): Boolean = runCatching {
        Settings.Global.getInt(svc.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    private fun startSettings(intent: Intent): Boolean =
        runCatching {
            svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }.isSuccess

    private fun settingsVisible(): Boolean = svc.first { it.pkg() == settingsPkg } != null

    private suspend fun waitSettings(ms: Long): Boolean {
        val end = now() + ms
        while (now() < end) {
            if (settingsVisible()) return true
            delay(300)
        }
        return false
    }

    /**
     * Открывает «Для разработчиков» и ждёт, пока окно «Настроек» реально появится на экране.
     * 1) обычный intent; 2) тот же intent с переходом к строке USB-отладки;
     * 3) общие «Настройки» → «Система» → «Для разработчиков».
     */
    private suspend fun openDeveloperOptions(): Boolean {
        if (startSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) && waitSettings(6_000)) return true

        val withKey = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "enable_adb")
        if (startSettings(withKey) && waitSettings(6_000)) return true

        if (!startSettings(Intent(Settings.ACTION_SETTINGS)) || !waitSettings(6_000)) return false
        val end = now() + 30_000
        var i = 0
        while (now() < end) {
            svc.first { it.pkg() == settingsPkg && it.eq(*DEV_OPTIONS) }?.let {
                svc.click(it)
                delay(1_200)
                if (svc.first { n -> n.pkg() == settingsPkg && isUsbDebugLabel(n.label()) } != null) return true
            }
            svc.first { it.pkg() == settingsPkg && it.eq("Система", "System") }?.let { svc.click(it); delay(1_000) }
            if (i % 3 == 2) svc.scroll(settingsPkg, true)
            delay(500)
            i++
        }
        return false
    }

    /** Строка про отладку по USB: «Отладка по USB», «Отладка USB», «USB debugging», «Режим отладки при подключении USB» и т.п. */
    private fun isUsbDebugLabel(raw: String): Boolean {
        val t = raw.replace('\u00A0', ' ').lowercase()
        if (t.isBlank() || t.length > 60) return false
        if (NOT_USB_ROW.any { t.contains(it.lowercase()) }) return false
        if (t.contains("wireless") || t.contains("беспровод") || t.contains("wi-fi") || t.contains("по wi")) return false
        val hasUsb = t.contains("usb")
        val hasDebug = t.contains("debug") || t.contains("отлад")
        return hasUsb && hasDebug
    }

    private fun usbTitle(): AccessibilityNodeInfo? =
        svc.first { it.pkg() == settingsPkg && it.eq(*USB_DEBUG) }
            ?: svc.first { it.pkg() == settingsPkg && isUsbDebugLabel(it.label()) }

    /** Переключатель в той же строке, что и заголовок [title]. */
    private fun rowSwitch(title: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = Rect().also { title.getBoundsInScreen(it) }
        val maxDy = svc.resources.displayMetrics.density * 48
        return svc.collect { it.pkg() == settingsPkg && it.isCheckable }
            .map { it to Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { (_, r) -> r.left >= t.left && Math.abs(r.centerY() - t.centerY()) <= maxDy }
            .minByOrNull { (_, r) -> Math.abs(r.centerY() - t.centerY()) }
            ?.first
    }

    private fun String.normHyphen(): String = this
        .replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-')
        .replace('\u2013', '-').replace('\u2212', '-').replace('\u00A0', ' ')
        .replace('\u202F', ' ').lowercase()

    /** Заголовок «Отладка по Wi-Fi» — по нему находим соседнюю строку «Отладка по USB». */
    private fun wifiTitle(): AccessibilityNodeInfo? = svc.first {
        val t = it.label().normHyphen()
        it.pkg() == settingsPkg && t.length < 40 &&
            (t.contains("отладка по wi") || t.contains("wi-fi debugging") || t.contains("wireless debugging") ||
                t.contains("беспроводная отладка"))
    }

    /**
     * Переключатель USB-отладки относительно строки «Отладка по Wi-Fi»: в списке он стоит на две строки выше
     * (USB → «Отозвать доступ…» → Wi-Fi). Берём ближайший переключатель выше Wi-Fi в пределах ~3 строк.
     */
    private fun switchAboveWifi(): AccessibilityNodeInfo? {
        val w = wifiTitle() ?: return null
        val wr = Rect().also { w.getBoundsInScreen(it) }
        val d = svc.resources.displayMetrics.density
        return svc.collect { it.pkg() == settingsPkg && it.isCheckable }
            .map { it to Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { (_, r) -> (wr.centerY() - r.centerY()) in (30 * d).toInt()..(170 * d).toInt() }
            .minByOrNull { (_, r) -> wr.centerY() - r.centerY() }
            ?.first
    }

    /** Номер строки списка (с 1, считая сверху всего списка, а не экрана) для узла или его родителя; -1 если неизвестен. */
    private fun rowNumber(node: AccessibilityNodeInfo): Int {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            n.collectionItemInfo?.let { if (it.rowIndex >= 0) return it.rowIndex + 1 }
            n = n.parent
        }
        return -1
    }

    /** Переключатель в строке с номером [USB_ROW_NUMBER] (по порядку сверху в списке). */
    private fun switchByRowNumber(): AccessibilityNodeInfo? =
        svc.collect { it.pkg() == settingsPkg && it.isCheckable }
            .firstOrNull { rowNumber(it) == USB_ROW_NUMBER }

    /** Переключатель «Отладка по USB»: по тексту строки, а если текст не распознан — по соседству с Wi-Fi. */
    private fun usbSwitch(): AccessibilityNodeInfo? =
        usbTitle()?.let { rowSwitch(it) } ?: switchByRowNumber() ?: switchAboveWifi()

    /**
     * Границы строки «Отозвать доступ для отладки по USB». Строка «Отладка по USB» на некоторых
     * прошивках скрыта от служб доступности (защитный флаг), зато соседняя строка видна —
     * она стоит ровно под ней.
     */
    private fun revokeRowBounds(): Rect? {
        val n = svc.first {
            val t = it.label().lowercase()
            it.pkg() == settingsPkg && t.length < 60 &&
                (t.contains("отозвать доступ") || t.contains("revoke usb"))
        } ?: return null
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return Rect().also { (c ?: n).getBoundsInScreen(it) }
    }

    /** Системный диалог «Разрешить отладку по USB?» → «ОК». */
    private fun confirmDialogButton(): AccessibilityNodeInfo? =
        svc.first { it.rid().endsWith("button1") && it.eq(*CONFIRM) }

    /** Текущее состояние совпадает с нужным. */
    private fun done(): Boolean = adbEnabled() == enable

    suspend fun run() {
        val what = if (enable) "включить" else "выключить"
        val stateWord = if (enable) "включена" else "выключена"
        say("▶ Отладка по USB: $what")

        // уже в нужном состоянии — «Настройки» не открываем
        if (done()) {
            say("✓ Отладка по USB уже $stateWord")
            return
        }
        if (!devEnabled()) {
            say("✗ Меню разработчика выключено — сначала нажмите «Разблокировать меню разработчика»")
            return
        }

        say("1/2 Открываю «Для разработчиков»")
        if (!openDeveloperOptions()) {
            say("✗ Окно «Настроек» не появилось")
            return
        }

        say("2/2 Нажимаю «Отладка по USB»")
        delay(1_500) // дать открыться экрану
        repeat(6) { if (usbSwitch() == null && revokeRowBounds() == null) { svc.scroll(settingsPkg, false); delay(250) } }

        val end = now() + 60_000
        var lastClick = 0L
        var scrolls = 0
        var down = true
        var lastScroll = 0L

        while (now() < end && !done()) {
            // диалог подтверждения появляется только при включении
            if (enable) {
                val confirm = confirmDialogButton()
                if (confirm != null) {
                    say("   подтверждаю диалог")
                    svc.click(confirm)
                    delay(800)
                    continue
                }
            }

            val sw = usbSwitch()
            val title = usbTitle()
            if (sw != null || title != null) {
                if (sw != null && sw.isChecked == enable) break // уже в нужном положении, ждём лишь ADB_ENABLED
                if (now() - lastClick > 3_000) {
                    // переключатель, а если его не нашли — тап по строке
                    if (sw != null) svc.click(sw) else svc.click(title!!)
                    lastClick = now()
                }
            } else if (revokeRowBounds() != null) {
                // строки USB-отладки нет в дереве — работаем по соседней «Отозвать доступ…»
                val rr = revokeRowBounds()!!
                val dm = svc.resources.displayMetrics
                val h = rr.height().toFloat()
                val y = rr.top - 0.7f * h          // центр строки «Отладка по USB» (двухстрочная) над «Отозвать…»
                val x = dm.widthPixels * 0.93f     // переключатель справа
                val lo = dm.heightPixels * 0.20f
                val hi = dm.heightPixels * 0.85f
                if (y in lo..hi) {
                    if (now() - lastClick > 4_000) {
                        svc.tapAt(x, y)
                        lastClick = now()
                        delay(1_500)
                    }
                } else if (now() - lastScroll > 1_500 && scrolls < 20) {
                    svc.scroll(settingsPkg, y > hi)   // строка слишком низко — вперёд, слишком высоко — назад
                    scrolls++
                    lastScroll = now()
                    delay(600)
                }
            } else if (svc.first { it.pkg() == settingsPkg } != null &&
                now() - lastScroll > 1_200 && scrolls < 20
            ) {
                // строки не видно — листаем «Настройки», на краю разворачиваемся
                if (!svc.scroll(settingsPkg, down)) down = !down
                scrolls++
                lastScroll = now()
                delay(600) // дать списку остановиться
            }
            delay(400)
        }

        // даём системе время записать настройку
        val verify = now() + 3_000
        while (now() < verify && !done()) delay(300)

        if (done()) {
            say("✓ Готово: отладка по USB $stateWord")
        } else {
            say("✗ Не удалось $what отладку по USB")
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
