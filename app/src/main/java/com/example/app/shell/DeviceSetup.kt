package com.example.app.shell

/**
 * Набор команд «adb shell ...», которые выполняются на самом устройстве кнопкой
 * на вкладке «Настройка». Пароль Wi-Fi вынесен сюда, в лог он не попадает
 * (в интерфейсе показывается только label).
 */
object DeviceSetup {

    const val WIFI_SSID = "HUAWEI-B315-AFCA"
    const val WIFI_PASSWORD = "HR63B1DMTJ4"

    /**
     * Яркость как на скриншоте: ползунок заполнен примерно на 20%.
     * Значение для settings put system screen_brightness (шкала 0..255).
     * В Android 9+ ползунок нелинейный, поэтому 20% на ползунке ≈ 5 из 255.
     * Хотите ярче — увеличьте число (например, 20 ≈ 40% на ползунке, 50 ≈ 60%).
     */
    private const val BRIGHTNESS = 55

    private const val IME = "org.dslul.openboard.inputmethod.latin/.LatinIME"

    data class Step(val label: String, val command: String)

       val steps: List<Step> = listOf(
        Step("Wi-Fi: подключение к $WIFI_SSID",
            "cmd wifi connect-network \"$WIFI_SSID\" wpa2 \"$WIFI_PASSWORD\""),
        Step("Режим энергосбережения", "settings put global low_power 1"),
        Step("Отключить постоянный поиск Wi-Fi (сканирование сетей)", "settings put global wifi_scan_always_enabled 0"),
        Step("Отключить Google Play services", "pm disable-user --user 0 com.google.android.gms"),
        Step("Беззвучный режим", "cmd audio set-ringer-mode SILENT"),
        Step("Яркость: автояркость выкл", "settings put system screen_brightness_mode 0"),
        Step("Яркость: низкая (~20% на ползунке)", "settings put system screen_brightness $BRIGHTNESS"),
 /**       Step("Отключить SDK Sandbox", "pm disable-user --user 0 com.google.android.sdksandbox"),
    *   Step("Отключить UWB resources", "pm disable-user --user 0 com.google.android.uwb.resources"),
    *   Step("Отключить Cell Broadcast (Google)", "pm disable-user --user 0 com.google.android.cellbroadcastreceiver"),
    *   Step("Отключить Cell Broadcast (AOSP)", "pm disable-user --user 0 com.android.cellbroadcastreceiver"),
    */   Step("Отключить Cell Broadcast overlay", "pm disable-user --user 0 com.android.cellbroadcast.overlay"),
        Step("Автоопределение часового пояса: выкл", "settings put global auto_time_zone 0"),
        Step("Часовой пояс: Europe/Moscow", "cmd alarm set-timezone Europe/Moscow"),
        Step("Клавиатура OpenBoard: включить", "ime enable $IME"),
        Step("Клавиатура OpenBoard: выбрать", "ime set $IME"),
        Step("Кнопка питания: долгое нажатие → меню выключения/перезагрузки",
            "settings put global power_button_long_press 1"),
        Step("Кнопка питания: очень долгое нажатие → без действия",
            "settings put global power_button_very_long_press 0")
    )
}
