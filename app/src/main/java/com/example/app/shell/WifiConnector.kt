package com.example.app.shell

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion

/**
 * Подключение к Wi-Fi штатным API (WifiNetworkSuggestion) — без Shizuku и без root.
 * Сеть добавляется как «предложение» приложения: система сама подключается к ней, когда сеть в зоне
 * действия. При первом добавлении Android показывает уведомление с просьбой разрешить приложению
 * предлагать сети — его нужно подтвердить.
 */
object WifiConnector {

    data class Outcome(val ok: Boolean, val message: String)

    fun connect(context: Context, ssid: String, password: String): Outcome {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .setIsInitialAutojoinEnabled(true)
            .build()

        // Убираем прошлое предложение с теми же параметрами, чтобы обновить его
        wifi.removeNetworkSuggestions(listOf(suggestion))

        val outcome = when (wifi.addNetworkSuggestions(listOf(suggestion))) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                Outcome(true, "Сеть $ssid добавлена. Если появится уведомление — разрешите приложению предлагать сети.")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                Outcome(true, "Сеть $ssid уже добавлена.")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                Outcome(false, "Приложению запрещено предлагать сети. Разрешите: Настройки → Wi-Fi → Настройки Wi-Fi → Предложенные сети.")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
                Outcome(false, "Превышен лимит предложенных сетей для приложения.")
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN ->
                Outcome(false, "Добавление сетей запрещено администратором устройства.")
            else -> Outcome(false, "Не удалось добавить сеть.")
        }

        return if (outcome.ok && !wifi.isWifiEnabled) {
            outcome.copy(message = outcome.message + " Wi-Fi сейчас выключен — включите его.")
        } else outcome
    }
}
