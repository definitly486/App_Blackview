package com.example.app.shell

/**
 * Блокировка фоновой работы, уведомлений и геолокации для выбранных приложений.
 * Команды — те же, что в скрипте с `adb shell`, но без префикса `adb shell`:
 * они выполняются через [AdbShell] (Shizuku, права shell).
 */
object BlockPermissions {

    /** Пакеты для блокировки фона и уведомлений. */
    private val backgroundPackages = listOf(
        "org.dslul.openboard.inputmethod.latin",
        "ru.yandex.yandexmaps",
        "com.deniscerri.ytdl",
        "org.cromite.cromite",
        "com.ghisler.android.TotalCommander",
        "org.wisso.newpipematerial",
        "com.qflair.browserq",
        "org.amnezia.vpn",
        "org.fossify.messages",
        "com.simplemobiletools.contacts.pro",
        "com.simplemobiletools.dialer",
        "com.saggitt.omega",
        "dev.imranr.obtainium.fdroid",
        "net.sourceforge.opencamera",
        "com.brouken.player",
        "org.schabi.newpipe",
        "org.sufficientlysecure.keychain",
        "com.blackview.launcher",
        "is.xyz.mpv"
    )

    /** Пакеты для блокировки геолокации (как во втором цикле скрипта — без Яндекс Карт). */
    private val locationPackages = backgroundPackages - "ru.yandex.yandexmaps"

    /** Один шаг на пакет: 4 команды блокировки фона/уведомлений. */
    private fun backgroundStep(pkg: String) = DeviceSetup.Step(
        label = "Фон и уведомления: $pkg",
        command = "if pm path $pkg >/dev/null 2>&1; then " +
            "pm revoke $pkg android.permission.POST_NOTIFICATIONS; " +
            "pm set-permission-flags $pkg android.permission.POST_NOTIFICATIONS user-set user-fixed; " +
            "cmd appops set $pkg RUN_IN_BACKGROUND ignore; " +
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore; " +
            "else echo 'Package not installed, skipping.'; fi"
    )

    /** Один шаг на пакет: блокировка геолокации, если пакет установлен. */
    private fun locationStep(pkg: String) = DeviceSetup.Step(
        label = "Геолокация: $pkg",
        command = "if pm path $pkg >/dev/null 2>&1; then " +
            "cmd appops set $pkg FINE_LOCATION ignore; " +
            "cmd appops set $pkg COARSE_LOCATION ignore; " +
            "else echo 'Package not installed, skipping.'; fi"
    )

    val steps: List<DeviceSetup.Step> =
        backgroundPackages.map(::backgroundStep) + locationPackages.map(::locationStep)
}
