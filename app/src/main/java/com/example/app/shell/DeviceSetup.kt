package com.example.app.shell

/**
 * Набор команд «adb shell ...», которые выполняются на самом устройстве кнопкой
 * на вкладке «Настройка». Пароль Wi-Fi вынесен сюда, в лог он не попадает
 * (в интерфейсе показывается только label).
 */
object DeviceSetup {

    private const val WIFI_SSID = "HUAWEI-B315-AFCA"
    private const val WIFI_PASSWORD = "HR63B1DMTJ4"

    private const val IME = "org.dslul.openboard.inputmethod.latin/.LatinIME"

    data class Step(val label: String, val command: String)

    val steps: List<Step> = listOf(
        Step("Wi-Fi: подключение к $WIFI_SSID",
            "cmd wifi connect-network \"$WIFI_SSID\" wpa2 \"$WIFI_PASSWORD\""),
        Step("Режим энергосбережения", "settings put global low_power 1"),
        Step("Отключить Google Play services", "pm disable-user --user 0 com.google.android.gms"),
        Step("Беззвучный режим", "cmd audio set-ringer-mode SILENT"),
        Step("Отключить SDK Sandbox", "pm disable-user --user 0 com.google.android.sdksandbox"),
        Step("Отключить UWB resources", "pm disable-user --user 0 com.google.android.uwb.resources"),
        Step("Отключить Cell Broadcast (Google)", "pm disable-user --user 0 com.google.android.cellbroadcastreceiver"),
        Step("Отключить Cell Broadcast (AOSP)", "pm disable-user --user 0 com.android.cellbroadcastreceiver"),
        Step("Отключить Cell Broadcast overlay", "pm disable-user --user 0 com.android.cellbroadcast.overlay"),
        Step("Автоопределение часового пояса: выкл", "settings put global auto_time_zone 0"),
        Step("Часовой пояс: Europe/Moscow", "cmd alarm set-timezone Europe/Moscow"),
        Step("Клавиатура OpenBoard: включить", "ime enable $IME"),
        Step("Клавиатура OpenBoard: выбрать", "ime set $IME")
    )
}
