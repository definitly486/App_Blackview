package com.example.app.pairing

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.app.MainActivity
import com.example.app.shell.AdbShell
import com.example.app.shell.ShizukuInstaller
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Полный сценарий «Сопряжение» одной кнопкой:
 *
 *  1. Shizuku → «Сопряжение» (запускает его службу уведомления) → «Параметры разработчика»
 *  2. Настройки → «Беспроводная отладка» → включить → «Подключить с помощью кода»
 *  3. читает 6-значный код из окна и вводит его в уведомление Shizuku («Введите код подключения»)
 *  4. Shizuku → «Запустить» → ждёт запуска → «Разрешить всегда» для этого приложения
 *
 * Тексты кнопок сверены с исходниками Shizuku (en/ru) и AOSP Settings; для других языков
 * добавьте варианты в списки ниже.
 */
class PairingScript(private val svc: PairingAccessibilityService) {

    private companion object {
        const val SHIZUKU = ShizukuInstaller.PACKAGE

        val PAIRING_BTN = arrayOf("Pairing", "Сопряжение")
        val DEV_OPTIONS = arrayOf("Developer options", "Параметры разработчика", "Для разработчиков")
        const val DEV_OPTIONS_ID = "developer_options"
        val WIRELESS = arrayOf("Wireless debugging", "Wi-Fi debugging", "Беспроводная отладка", "Отладка по Wi-Fi", "Отладка по Wi-Fi")
        val USB_DEBUG = arrayOf("USB debugging", "Отладка по USB")
        val PAIR_ROW = arrayOf(
            "pairing code", "код подключения", "кода подключения", "коду подключения",
            "код сопряжения", "кода сопряжения"
        )
        val ALLOW_NETWORK = arrayOf("Allow", "Разрешить")
        val ENTER_CODE = arrayOf("Enter pairing code", "Введите код подключения", "Введите код сопряжения")
        val SEND = arrayOf("Send", "Отправить")
        val PAIR_OK = arrayOf("Pairing successful", "Сопряжение выполнено")
        val PAIR_FAIL = arrayOf("Pairing failed", "Не удалось", "Сопряжение не")
        val START_BTN = arrayOf("Start", "Запустить", "Запуск")
        val ALLOW_ALWAYS = arrayOf("Allow all the time", "Разрешить всегда")
        val CODE_FALLBACK = Regex("^\\s*\\d{3}\\s?\\d{3}\\s*$")
    }

    private fun say(s: String) = PairingAutomation.say(s)
    private fun now() = SystemClock.uptimeMillis()

    // ---------- работа с узлами ----------

    private fun AccessibilityNodeInfo.label(): String = (text ?: contentDescription)?.toString() ?: ""
    private fun AccessibilityNodeInfo.pkg(): String = packageName?.toString() ?: ""
    private fun AccessibilityNodeInfo.rid(): String = viewIdResourceName ?: ""
    private fun AccessibilityNodeInfo.top(): Int = Rect().also { getBoundsInScreen(it) }.top

    /** Разные виды дефиса/неразрывных пробелов приводим к обычным. */
    private fun String.norm(): String = this
        .replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-')
        .replace('\u2013', '-').replace('\u2212', '-').replace('\u00A0', ' ')

    private fun AccessibilityNodeInfo.eq(vararg v: String): Boolean {
        val t = label().trim().norm()
        return v.any { it.norm().equals(t, ignoreCase = true) }
    }

    private fun AccessibilityNodeInfo.has(vararg v: String): Boolean {
        val t = label().norm()
        return v.any { t.contains(it.norm(), ignoreCase = true) }
    }

    /** Пакет «Настроек» (на большинстве прошивок com.android.settings). */
    private val settingsPkg: String by lazy {
        svc.packageManager
            .resolveActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), 0)
            ?.activityInfo?.packageName ?: "com.android.settings"
    }

    private fun AccessibilityNodeInfo.inSettings(): Boolean = pkg() == settingsPkg

    /** Ждёт, пока probe что-то вернёт; при таймауте пишет в лог, что видно на экране. */
    private suspend fun <T : Any> waitFor(
        what: String,
        timeoutMs: Long,
        scrollPkg: String? = null,
        silent: Boolean = false,
        probe: () -> T?
    ): T? {
        val end = now() + timeoutMs
        var i = 0
        while (now() < end) {
            probe()?.let { return it }
            if (scrollPkg != null && i % 4 == 3) svc.scroll(scrollPkg, true)
            delay(400)
            i++
        }
        if (!silent) {
            say("   ✗ не дождался: $what")
            say("   на экране: ${svc.dumpScreen()}")
        }
        return null
    }

    // ---------- проверки ----------

    private fun hasWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun launchShizuku(): Boolean {
        val intent = svc.packageManager.getLaunchIntentForPackage(SHIZUKU) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { svc.startActivity(intent) }.isSuccess
    }

    /** Кнопка «Параметры разработчика»: по точному тексту (в описании выше та же фраза внутри длинного текста), затем по id. */
    private fun devOptionsButton(): AccessibilityNodeInfo? =
        svc.first { it.pkg() == SHIZUKU && it.eq(*DEV_OPTIONS) }
            ?: svc.first { it.pkg() == SHIZUKU && it.rid().endsWith(DEV_OPTIONS_ID) }

    /** Тот же intent, что использует Shizuku: сразу на страницу «Беспроводная отладка». */
    private fun openDeveloperOptions(): Boolean {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        return runCatching { svc.startActivity(intent) }.isSuccess
    }

    private fun shizukuVisible() = svc.first { it.pkg() == SHIZUKU } != null

    /** Возвращает на экран Shizuku: сначала прямым запуском, если система не дала — кнопкой «Назад». */
    private suspend fun returnToShizuku(): Boolean {
        launchShizuku()
        if (waitFor("окно Shizuku", 4_000, silent = true) { if (shizukuVisible()) true else null } != null) return true
        repeat(6) {
            svc.back()
            delay(700)
            if (shizukuVisible()) return true
        }
        return false
    }

    // ---------- сценарий ----------

    suspend fun run() {
        val ctx = svc.applicationContext

        say("▶ Сопряжение: старт")
        if (!ShizukuInstaller.isInstalled(ctx)) {
            say("✗ Shizuku не установлен — нажмите «Установить Shizuku»")
            return
        }
        if (AdbShell.isRunning()) {
            say("✓ Shizuku уже запущен")
            grantPermission()
            backToApp()
            return
        }
        if (!hasWifi(ctx)) {
            say("✗ Нет подключения к Wi-Fi (нужен для беспроводной отладки) — нажмите «Подключиться к Wi-Fi»")
            return
        }

        // 1. Shizuku → Сопряжение
        say("1/6 Открываю Shizuku и жму «Сопряжение»")
        if (!launchShizuku()) { say("   ✗ не удалось открыть Shizuku"); return }
        val pairBtn = waitFor("кнопка «Сопряжение» в Shizuku", 15_000, scrollPkg = SHIZUKU) {
            svc.first { it.pkg() == SHIZUKU && it.eq(*PAIRING_BTN) }
                ?: svc.first { it.pkg() == SHIZUKU && it.rid() == "android:id/button2" }
        } ?: return
        svc.click(pairBtn)

        // 2. Shizuku → Параметры разработчика
        say("2/6 Открываю параметры разработчика")
        val dev = waitFor(
            "кнопка «Параметры разработчика»", 8_000, scrollPkg = SHIZUKU, silent = true
        ) { devOptionsButton() }
        if (dev != null && svc.click(dev)) {
            say("   нажал кнопку в Shizuku")
        } else {
            say("   кнопку в Shizuku не нажать — открываю параметры разработчика напрямую")
            if (!openDeveloperOptions()) { say("   ✗ не удалось открыть параметры разработчика"); return }
        }

        // 3. Настройки → беспроводная отладка → окно с кодом
        say("3/6 Включаю беспроводную отладку и открываю окно с кодом")
        val code = obtainPairingCode() ?: return
        say("   код получен: $code")

        // 4. Код → уведомление Shizuku
        say("4/6 Ввожу код в уведомление Shizuku")
        if (!enterCodeInNotification(code)) return

        // 5. Запуск Shizuku
        say("5/6 Запускаю Shizuku")
        if (!returnToShizuku()) { say("   ✗ не удалось вернуться в Shizuku"); return }
        val start = waitFor("кнопка «Запустить» в карточке беспроводной отладки", 15_000, scrollPkg = SHIZUKU) {
            wirelessStartButton() ?: run {
                // если застряли на экране инструкции — выходим на главную Shizuku
                if (devOptionsButton() != null) svc.back()
                null
            }
        } ?: return
        svc.click(start)
        waitFor("запуск Shizuku (включена ли беспроводная отладка?)", 60_000) {
            if (AdbShell.isRunning()) true else null
        } ?: return
        say("   ✓ Shizuku запущен")

        // 6. Разрешение для приложения
        say("6/6 Выдаю разрешение этому приложению")
        grantPermission()

        backToApp()
        say("✓ Готово: Shizuku работает, команды доступны")
    }

    /** Кнопка запуска именно в карточке «Запуск через беспроводную отладку» (а не в карточке root). */
    private fun wirelessStartButton(): AccessibilityNodeInfo? {
        val titleTop = svc.collect { it.pkg() == SHIZUKU && it.has(*WIRELESS, "беспроводн") }
            .minOfOrNull { it.top() } ?: return null
        return svc.collect { it.pkg() == SHIZUKU && it.eq(*START_BTN) }
            .filter { it.top() > titleTop }
            .minByOrNull { it.top() }
    }

    /**
     * Работает в «Настройках»: открывает «Беспроводная отладка», включает её (подтверждает диалог),
     * нажимает «Подключить с помощью кода» и читает 6-значный код.
     */
    private suspend fun obtainPairingCode(): String? {
        val end = now() + 75_000
        var lastRow = 0L
        var lastPair = 0L
        var lastSwitch = 0L
        var lastScroll = 0L
        var scrolls = 0
        var down = true

        while (now() < end) {
            readCode()?.let { return it }

            // Диалог «Разрешить беспроводную отладку в этой сети?»
            svc.first { it.pkg() != SHIZUKU && it.rid().endsWith("button1") && it.eq(*ALLOW_NETWORK) }
                ?.let { svc.click(it) }

            // Дальше работаем только если окно «Настроек» на экране; в других приложениях ничего не трогаем
            if (svc.first { it.inSettings() } == null) { delay(500); continue }

            val pairRow = svc.first { it.inSettings() && it.has(*PAIR_ROW) }
            val hasWireless = svc.first { it.inSettings() && it.has(*WIRELESS) } != null
            val hasUsb = svc.first { it.inSettings() && it.has(*USB_DEBUG) } != null
            val onWirelessPage = pairRow != null || (hasWireless && !hasUsb)

            when {
                pairRow != null -> {
                    if (now() - lastPair > 4_000) { svc.click(pairRow); lastPair = now() }
                }
                onWirelessPage -> {
                    // страница «Отладка по Wi-Fi», пункта с кодом ещё нет — включаем главный переключатель
                    if (now() - lastSwitch > 4_000) {
                        svc.first { it.inSettings() && it.isCheckable && !it.isChecked }
                            ?.let { svc.click(it); lastSwitch = now() }
                    }
                }
                hasWireless -> {
                    // список «Для разработчиков»: открываем строку «Отладка по Wi-Fi»
                    if (now() - lastRow > 3_000) {
                        svc.first { it.inSettings() && it.has(*WIRELESS) }?.let { svc.click(it); lastRow = now() }
                    }
                }
                else -> {
                    // строки не видно: аккуратно листаем только «Настройки», не больше 12 раз; на краю — разворачиваем
                    if (now() - lastScroll > 1_500 && scrolls < 12) {
                        if (!svc.scroll(settingsPkg, down)) down = !down
                        scrolls++
                        lastScroll = now()
                    }
                }
            }
            delay(400)
        }
        say("   ✗ не дождался окна с кодом сопряжения")
        say("   на экране: ${svc.dumpScreen()}")
        return null
    }

    private fun readCode(): String? {
        svc.first { it.rid().endsWith("pairing_code") && Regex("\\d{6}").containsMatchIn(it.label()) }
            ?.let { return it.label().filter { c -> c.isDigit() }.take(6) }
        return svc.first { it.inSettings() && CODE_FALLBACK.matches(it.label()) }
            ?.label()?.filter { it.isDigit() }
    }

    /** Открывает шторку, жмёт «Введите код подключения», вводит код и отправляет. */
    private suspend fun enterCodeInNotification(code: String): Boolean {
        svc.openShade()
        delay(900)

        var lastExpand = 0L
        val action = waitFor("кнопка «Введите код подключения» в уведомлении Shizuku", 30_000) {
            svc.first { it.has(*ENTER_CODE) } ?: run {
                // уведомление свёрнуто — раскрываем
                if (now() - lastExpand > 4_000) {
                    svc.first { it.pkg() != SHIZUKU && it.eq("Shizuku") }?.let { svc.click(it) }
                    lastExpand = now()
                }
                null
            }
        } ?: return false
        svc.click(action)

        val input = waitFor("поле ввода кода в уведомлении", 8_000) { svc.first { it.isEditable } } ?: return false
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(400)

        val send = svc.first { it.rid().endsWith("remote_input_send") }
            ?: svc.first { it.pkg() != SHIZUKU && it.eq(*SEND) }
        if (send != null) {
            svc.click(send)
        } else {
            input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        val result = waitFor("результат сопряжения", 20_000, silent = true) {
            when {
                svc.first { it.has(*PAIR_OK) } != null -> true
                svc.first { it.has(*PAIR_FAIL) } != null -> false
                else -> null
            }
        }
        when (result) {
            true -> say("   ✓ сопряжение выполнено")
            false -> { say("   ✗ Shizuku сообщил, что сопряжение не удалось (неверный код?)"); return false }
            null -> say("   ? подтверждение не увидел — пробую запускать Shizuku дальше")
        }
        svc.back() // закрыть шторку
        delay(500)
        return true
    }

    /** Нажимает «Разрешить всегда» в окне запроса Shizuku для этого приложения. */
    private suspend fun grantPermission() {
        if (AdbShell.hasPermission()) { say("   ✓ разрешение уже выдано"); return }
        coroutineScope {
            val request = async { AdbShell.requestPermission() }
            val btn = waitFor("кнопка «Разрешить всегда»", 15_000) {
                svc.first { it.pkg() == SHIZUKU && it.eq(*ALLOW_ALWAYS) }
            }
            btn?.let { svc.click(it) }
            val granted = withTimeoutOrNull(10_000) { request.await() } ?: false
            say(if (granted) "   ✓ разрешение выдано" else "   ✗ разрешение не выдано")
        }
    }

    private fun backToApp() {
        val ctx = svc.applicationContext
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { svc.startActivity(intent) }
    }
}
