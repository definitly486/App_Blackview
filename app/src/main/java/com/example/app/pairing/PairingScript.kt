package com.example.app.pairing

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.example.app.MainActivity
import com.example.app.shell.AdbShell
import com.example.app.shell.DeviceSetup
import com.example.app.shell.WifiConnector
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
        val WIRELESS_TITLE = arrayOf(
            "Wireless debugging", "Wi-Fi debugging", "Беспроводн", "Отладка по Wi-Fi", "отладку по Wi-Fi", "отладке по Wi-Fi"
        )
        val USB_DEBUG = arrayOf("USB debugging", "Отладка по USB")
        val PAIR_ROW = arrayOf(
            "pairing code", "код подключения", "кода подключения", "коду подключения",
            "код сопряжения", "кода сопряжения"
        )
        val NOTIF_BTN = arrayOf("Notification settings", "Настройки уведомлений")
        const val PERM_ALLOW_ID = "permission_allow_button"
        val ALLOW_NETWORK = arrayOf("Allow", "Разрешить")
        val ENTER_CODE = arrayOf("Enter pairing code", "Введите код подключения", "Введите код сопряжения")
        val SEND = arrayOf("Send", "Отправить")
        val PAIR_OK = arrayOf("Pairing successful", "Сопряжение выполнено")
        val PAIR_FAIL = arrayOf("Pairing failed", "Не удалось", "Сопряжение не")
        val START_BTN = arrayOf("Start", "Запустить", "Запуск")
        val ALLOW_ALWAYS = arrayOf("Allow all the time", "Разрешить всегда")
        val INSTALL_BTN = arrayOf(
            "Install", "Update", "Установить", "Обновить",
            "Install anyway", "Установить всё равно", "Установить все равно", "Всё равно установить", "Все равно установить"
        )
        val DONE_BTN = arrayOf("Done", "Готово")
        const val WIFI_WAIT_MS = 45_000L
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

    @Suppress("DEPRECATION")
    private fun hasWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // allNetworks: роутер без интернета (Huawei B315) не становится «активной» сетью, пока есть мобильные данные
        return cm.allNetworks.any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun developerOptionsEnabled(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(
            ctx.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
    }.getOrDefault(false)

    /** Если меню разработчика скрыто — включает его (7 нажатий на «Номер сборки») тем же сценарием, что и отдельная кнопка. */
    private suspend fun ensureDeveloperOptions(ctx: Context): Boolean {
        if (developerOptionsEnabled(ctx)) return true
        say("   Меню разработчика не включено — разблокирую")
        DeveloperUnlockScript(svc).run()
        if (developerOptionsEnabled(ctx)) return true
        say("   ✗ меню разработчика не включилось — включите вручную и повторите")
        return false
    }

    private fun isInstallerWindow(n: AccessibilityNodeInfo): Boolean = n.pkg().contains("installer", ignoreCase = true)

    /**
     * Если Shizuku нет — ставит его из вшитого APK: сам включает «Установку из этого источника»,
     * жмёт «Установить» в системном установщике и ждёт появления пакета.
     */
    private suspend fun ensureShizukuInstalled(ctx: Context): Boolean {
        if (ShizukuInstaller.isInstalled(ctx)) return true
        say("0/6 Shizuku не установлен — устанавливаю из APK приложения")

        var r = ShizukuInstaller.install(ctx)
        if (r is ShizukuInstaller.Result.NeedInstallPermission) {
            say("   включаю разрешение «Установка неизвестных приложений»")
            val sw = waitFor("переключатель установки из этого источника", 10_000, silent = true) {
                svc.first { it.inSettings() && it.isCheckable && it.isEnabled }
            }
            if (sw != null && !sw.isChecked) svc.click(sw)
            delay(1_000)
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                say("   ✗ не удалось включить разрешение — включите его вручную и повторите")
                say("   на экране: ${svc.dumpScreen()}")
                return false
            }
            svc.back()
            delay(800)
            r = ShizukuInstaller.install(ctx)
        }
        if (r is ShizukuInstaller.Result.Error) {
            say("   ✗ не удалось запустить установку: ${r.message}")
            return false
        }
        if (r !is ShizukuInstaller.Result.Started) {
            say("   ✗ установка не началась")
            return false
        }

        var lastClick = 0L
        val ok = waitFor("установка Shizuku (кнопка «Установить»)", 90_000) {
            if (ShizukuInstaller.isInstalled(ctx)) true else {
                if (now() - lastClick > 2_000) {
                    val btn = svc.first { isInstallerWindow(it) && it.eq(*INSTALL_BTN) }
                    if (btn != null && svc.click(btn)) lastClick = now()
                }
                null
            }
        } ?: return false

        delay(1_000)
        svc.first { isInstallerWindow(it) && it.eq(*DONE_BTN) }?.let { svc.click(it) }
        say("   ✓ Shizuku установлен")
        return ok
    }

    /**
     * Если Wi-Fi не подключён — включает его (при необходимости) и подключается к сети из [DeviceSetup]
     * через WifiNetworkSuggestion; подтверждает системный запрос на разрешение предлагать сети.
     */
    @Suppress("DEPRECATION")
    private suspend fun ensureWifi(ctx: Context): Boolean {
        if (hasWifi(ctx)) return true
        say("   Wi-Fi не подключён — подключаюсь к ${DeviceSetup.WIFI_SSID}")

        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            say("   Wi-Fi выключен — включаю")
            runCatching { wm.setWifiEnabled(true) } // на Android 10+ игнорируется, тогда — через панель
            delay(1_000)
            if (!wm.isWifiEnabled) {
                runCatching {
                    svc.startActivity(
                        Intent(android.provider.Settings.Panel.ACTION_WIFI)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                waitFor("переключатель Wi-Fi", 8_000, silent = true) {
                    if (wm.isWifiEnabled) true else {
                        svc.first { it.isCheckable && !it.isChecked && it.isEnabled && !it.pkg().startsWith(ctx.packageName) }
                            ?.let { svc.click(it) }
                        null
                    }
                }
                svc.back() // закрыть панель
                delay(500)
            }
            if (!wm.isWifiEnabled) {
                say("   ✗ не удалось включить Wi-Fi — включите вручную и повторите")
                return false
            }
        }

        val res = WifiConnector.connect(ctx, DeviceSetup.WIFI_SSID, DeviceSetup.WIFI_PASSWORD)
        say((if (res.ok) "   " else "   ✗ ") + res.message)
        if (!res.ok) return false

        val start = now()
        val end = start + WIFI_WAIT_MS
        var asked = false
        while (now() < end) {
            if (hasWifi(ctx)) { say("   ✓ Wi-Fi подключён"); delay(1_500); return true }
            // при первом добавлении Android спрашивает в уведомлении «Разрешить предлагать сети?»
            if (!asked && now() > start + 4_000) {
                asked = true
                svc.openShade()
                delay(900)
                val allow = waitFor("кнопка «Разрешить» в запросе о сетях", 5_000, silent = true) {
                    svc.first { it.pkg() != ctx.packageName && it.pkg() != SHIZUKU && it.eq(*ALLOW_NETWORK) }
                }
                if (allow != null) { svc.click(allow); say("   разрешил приложению предлагать сети") }
                delay(500)
                svc.back()
            }
            delay(1_000)
        }
        say("   ✗ не удалось подключиться к ${DeviceSetup.WIFI_SSID} (сеть в зоне действия? пароль верный?)")
        return false
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
        if (!ensureShizukuInstalled(ctx)) return
        if (AdbShell.isRunning()) {
            say("✓ Shizuku уже запущен")
            grantPermission()
            backToApp()
            return
        }
        // Беспроводная отладка живёт в меню разработчика
        if (!ensureDeveloperOptions(ctx)) return
        // Беспроводной отладке нужен Wi-Fi
        if (!ensureWifi(ctx)) return

        // 1. Shizuku → Сопряжение
        say("1/6 Открываю Shizuku и жму «Сопряжение»")
        if (!launchShizuku()) { say("   ✗ не удалось открыть Shizuku"); return }
        val pairBtn = waitFor("кнопка «Сопряжение» в Shizuku", 15_000, scrollPkg = SHIZUKU) {
            svc.first { it.pkg() == SHIZUKU && it.eq(*PAIRING_BTN) }
                ?: svc.first { it.pkg() == SHIZUKU && it.rid() == "android:id/button2" }
        } ?: return
        svc.click(pairBtn)

        // 1.5. Без разрешения на уведомления Shizuku прячет инструкцию и не покажет поле для кода
        if (!ensureShizukuNotifications()) return

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

    private fun notifBannerButton(): AccessibilityNodeInfo? =
        svc.first { it.pkg() == SHIZUKU && it.eq(*NOTIF_BTN) }

    /**
     * Если на экране «Сопряжение» красная плашка «выдайте Shizuku разрешение на показ уведомлений» —
     * жмёт «Настройки уведомлений», включает переключатель и возвращается в Shizuku.
     * Заодно соглашается на системный запрос POST_NOTIFICATIONS, если он появился.
     */
    private suspend fun ensureShizukuNotifications(): Boolean {
        val seen = waitFor("экран сопряжения Shizuku", 10_000, silent = true) {
            // системный запрос «Разрешить Shizuku отправлять уведомления?»
            svc.first { it.rid().endsWith(PERM_ALLOW_ID) }?.let { svc.click(it) }
            when {
                notifBannerButton() != null -> "banner"
                devOptionsButton() != null -> "ok"
                else -> null
            }
        }
        if (seen != "banner") return true

        say("   Shizuku просит разрешить уведомления — включаю")
        repeat(3) {
            val btn = notifBannerButton() ?: return true.also { say("   ✓ уведомления Shizuku включены") }
            svc.click(btn)
            delay(1_200)
            enableNotificationSwitch()
            backToShizukuScreen()
            delay(800)
        }
        if (notifBannerButton() == null) {
            say("   ✓ уведомления Shizuku включены")
            return true
        }
        say("   ✗ не удалось включить уведомления Shizuku — включите вручную и повторите")
        say("   на экране: ${svc.dumpScreen()}")
        return false
    }

    /** На странице уведомлений Shizuku включает выключенные переключатели (сверху вниз, не более 3). */
    private suspend fun enableNotificationSwitch() {
        val end = now() + 12_000
        val idleLimit = now() + 4_000
        var clicks = 0
        while (now() < end && clicks < 3) {
            svc.first { it.rid().endsWith(PERM_ALLOW_ID) }?.let { svc.click(it); delay(700) }
            val sw = svc.collect { it.inSettings() && it.isCheckable && !it.isChecked && it.isEnabled }
                .minByOrNull { it.top() }
            if (sw != null) {
                svc.click(sw)
                clicks++
                delay(900)
            } else {
                if (clicks > 0 || now() > idleLimit) break
                delay(400)
            }
        }
    }

    /** Выходит из «Настроек» назад в Shizuku кнопкой «Назад» (запуск заново сбросил бы экран сопряжения). */
    private suspend fun backToShizukuScreen() {
        repeat(5) {
            if (svc.first { it.inSettings() } == null && shizukuVisible()) return
            svc.back()
            delay(700)
        }
    }

    /** Кнопка запуска именно в карточке «Запуск через беспроводную отладку» (а не в карточке root). */
    private fun wirelessStartButton(): AccessibilityNodeInfo? {
        val starts = svc.collect { it.pkg() == SHIZUKU && it.eq(*START_BTN) }
        if (starts.isEmpty()) return null
        // Заголовок карточки: «Запуск через отладку по Wi-Fi» (в винительном падеже!), «Start via Wireless debugging»
        val titleTop = svc.collect { it.pkg() == SHIZUKU && it.has(*WIRELESS_TITLE) }
            .minOfOrNull { it.top() }
        // Заголовок не нашли — берём верхнюю кнопку: карточка беспроводной отладки выше карточки root
        return if (titleTop == null) starts.minByOrNull { it.top() }
        else starts.filter { it.top() > titleTop }.minByOrNull { it.top() }
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
            val wirelessTitle = svc.first { it.inSettings() && it.eq(*WIRELESS) }
            // мы на списке «Для разработчиков» (а не внутри страницы «Отладка по Wi-Fi»)
            val onDevList = svc.first { it.inSettings() && it.eq(*DEV_OPTIONS) } != null ||
                svc.first { it.inSettings() && it.has(*USB_DEBUG) } != null

            when {
                pairRow != null -> {
                    if (now() - lastPair > 4_000) { svc.click(pairRow); lastPair = now() }
                }
                wirelessTitle != null -> {
                    // трогаем ТОЛЬКО переключатель в строке «Отладка по Wi-Fi», остальные ползунки не касаемся
                    val sw = wirelessSwitch(wirelessTitle)
                    if (sw != null && !sw.isChecked) {
                        if (now() - lastSwitch > 4_000) { svc.click(sw); lastSwitch = now() }
                    } else if (onDevList) {
                        // переключатель включён — открываем саму страницу тапом по названию строки
                        // (тап по координатам, чтобы не нажать на переключатель в той же строке)
                        if (now() - lastRow > 3_000) { svc.tap(wirelessTitle); lastRow = now() }
                    }
                    // иначе мы уже на странице с включённым переключателем — ждём пункт с кодом
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

    /**
     * Переключатель, который стоит в одной строке с заголовком [title]: ближайший по вертикали
     * checkable-узел справа от названия. Никаких других переключателей не возвращает.
     */
    private fun wirelessSwitch(title: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val t = Rect().also { title.getBoundsInScreen(it) }
        val maxDy = svc.resources.displayMetrics.density * 40
        return svc.collect { it.inSettings() && it.isCheckable }
            .map { it to Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { (_, r) -> r.left >= t.left && Math.abs(r.centerY() - t.centerY()) <= maxDy }
            .minByOrNull { (_, r) -> Math.abs(r.centerY() - t.centerY()) }
            ?.first
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
