package com.example.app.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app.R
import com.example.app.shell.AdbShell
import kotlinx.coroutines.launch

/**
 * Агрессивный режим видеоплеера.
 *
 * Требуется запущенный Shizuku с разрешением.
 * Оставляет живыми минимальный Android-фундамент, это приложение и mpv.
 * Остальные пакеты останавливаются и suspend'ятся.
 *
 * На время режима отключаются Wi‑Fi, мобильные данные и Bluetooth.
 * После «Разморозить» состояние connectivity возвращается.
 */
class VideoModeFragment : Fragment(R.layout.fragment_video_mode) {

    // Важный UX: при открытии вкладки режим считается обычным,
    // пока пользователь НЕ нажал «ЗАМОРОЗИТЬ» в этом запуске приложения.
    // Старый state-файл сам по себе больше не переводит UI в «активен».
    private var modeStartedInThisSession = false

    private lateinit var status: TextView
    private lateinit var freezeButton: Button
    private lateinit var unfreezeButton: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        status = view.findViewById(R.id.videoModeStatus)
        freezeButton = view.findViewById(R.id.btnFreezeVideoMode)
        unfreezeButton = view.findViewById(R.id.btnUnfreezeVideoMode)

        freezeButton.setOnClickListener { runVideoMode(freeze = true) }
        unfreezeButton.setOnClickListener { runVideoMode(freeze = false) }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!AdbShell.isRunning()) {
                status.text = "Shizuku не запущен.\nЗапустите Shizuku, затем нажмите кнопку."
                freezeButton.isEnabled = true
                unfreezeButton.isEnabled = true
                return@launch
            }

            if (!AdbShell.hasPermission()) {
                status.text = "Shizuku запущен, но разрешение ещё не выдано.\nНажмите «Заморозить» или «Разморозить» — запрос появится автоматически."
                freezeButton.isEnabled = true
                unfreezeButton.isEnabled = true
                return@launch
            }

            // Не читаем старый state-файл при открытии вкладки.
            // UI становится «активным» только после нажатия «ЗАМОРОЗИТЬ».
            val active = modeStartedInThisSession

            status.text = if (active) {
                "🔴 РЕЖИМ ВИДЕО АКТИВЕН\nWi‑Fi / мобильные данные / Bluetooth отключены.\nФоновые приложения заморожены."
            } else {
                "🟢 Обычный режим\nНажмите «Заморозить», чтобы превратить телефон в видеоплеер."
            }

            freezeButton.isEnabled = !active
            // Всегда оставляем «Разморозить» доступной для аварийного восстановления
            // после перезапуска приложения.
            unfreezeButton.isEnabled = true
        }
    }

    private fun runVideoMode(freeze: Boolean) {
        val context = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            if (!AdbShell.isRunning()) {
                Toast.makeText(context, "Сначала запустите Shizuku", Toast.LENGTH_LONG).show()
                refreshStatus()
                return@launch
            }

            if (!AdbShell.hasPermission() && !AdbShell.requestPermission()) {
                Toast.makeText(context, "Разрешение Shizuku не выдано", Toast.LENGTH_LONG).show()
                refreshStatus()
                return@launch
            }

            freezeButton.isEnabled = false
            unfreezeButton.isEnabled = false
            status.text = if (freeze) "⏳ Заморозка…" else "⏳ Разморозка…"

            val command = if (freeze) buildFreezeCommand(context.packageName) else buildUnfreezeCommand()
            val result = AdbShell.exec(context, command)

            if (result.ok) {
                modeStartedInThisSession = freeze
                Toast.makeText(
                    context,
                    if (freeze) "Телефон переведён в режим видеоплеера"
                    else "Обычный режим восстановлен",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "Команда завершилась с ошибкой: ${result.output.take(200)}",
                    Toast.LENGTH_LONG
                ).show()
            }

            refreshStatus()
        }
    }

    private fun buildFreezeCommand(appPackage: String): String = """
        STATE_FILE=$STATE_FILE
        if [ -f "${'$'}STATE_FILE" ]; then
            echo "ALREADY_ACTIVE"
            exit 2
        fi

        umask 077
        : > "${'$'}STATE_FILE"

        echo "wifi=$(settings get global wifi_on 2>/dev/null)" >> "${'$'}STATE_FILE"
        echo "bluetooth=$(settings get global bluetooth_on 2>/dev/null)" >> "${'$'}STATE_FILE"
        echo "mobile_data=$(settings get global mobile_data 2>/dev/null)" >> "${'$'}STATE_FILE"
        echo "airplane=$(settings get global airplane_mode_on 2>/dev/null)" >> "${'$'}STATE_FILE"

        KEEP="$CORE_KEEP $appPackage is.xyz.mpv"

        # Останавливаем и suspend'им всё остальное.
        pm list packages 2>/dev/null | sed 's/^package://' | while read -r PKG; do
            [ -z "${'$'}PKG" ] && continue
            SKIP=0
            for K in ${'$'}KEEP; do
                [ "${'$'}PKG" = "${'$'}K" ] && SKIP=1 && break
            done
            [ "${'$'}SKIP" -eq 1 ] && continue

            # Дополнительная защита: эти компоненты нужны для открытия файлов,
            # Intent/Uris, Shizuku/Shell и работы mpv с хранилищем.
            case "${'$'}PKG" in
                com.android.shell|com.android.documentsui|com.google.android.documentsui|                com.android.intentresolver|android.intentresolver|                com.android.externalstorage|com.android.providers.media|                com.google.android.providers.media.module|                com.android.providers.downloads|com.android.providers.downloads.ui|                com.android.systemui|com.android.permissioncontroller|                com.google.android.permissioncontroller)
                    continue
                    ;;
            esac

            echo "${'$'}PKG" >> "${'$'}STATE_FILE"
            am force-stop "${'$'}PKG" >/dev/null 2>&1
            cmd package suspend --user 0 "${'$'}PKG" >/dev/null 2>&1
        done

        svc wifi disable >/dev/null 2>&1
        svc data disable >/dev/null 2>&1
        svc bluetooth disable >/dev/null 2>&1

        # Отключаем радиочасть штатным ConnectivityService.
        cmd connectivity airplane-mode enable >/dev/null 2>&1

        # Пытаемся дать mpv повышенный CPU-приоритет.
        MPV_PID=$(pidof is.xyz.mpv 2>/dev/null)
        if [ -n "${'$'}MPV_PID" ]; then
            renice -n -10 -p "${'$'}MPV_PID" >/dev/null 2>&1
        fi

        echo "ACTIVE=1" >> "${'$'}STATE_FILE"
        echo "OK"
    """.trimIndent()

    private fun buildUnfreezeCommand(): String = """
        STATE_FILE=$STATE_FILE
        if [ ! -f "${'$'}STATE_FILE" ]; then
            echo "NOT_ACTIVE"
            exit 0
        fi

        sed -n 's/^\(com\..*\)$/\1/p; s/^\(android\..*\)$/\1/p; s/^\(is\..*\)$/\1/p' "${'$'}STATE_FILE" |
        while read -r PKG; do
            [ -z "${'$'}PKG" ] && continue
            cmd package unsuspend --user 0 "${'$'}PKG" >/dev/null 2>&1
        done

        WIFI=$(sed -n 's/^wifi=//p' "${'$'}STATE_FILE" | head -n 1)
        BT=$(sed -n 's/^bluetooth=//p' "${'$'}STATE_FILE" | head -n 1)
        DATA=$(sed -n 's/^mobile_data=//p' "${'$'}STATE_FILE" | head -n 1)
        AIRPLANE=$(sed -n 's/^airplane=//p' "${'$'}STATE_FILE" | head -n 1)

        if [ "${'$'}AIRPLANE" = "0" ]; then
            cmd connectivity airplane-mode disable >/dev/null 2>&1
        fi

        if [ "${'$'}WIFI" = "1" ]; then svc wifi enable >/dev/null 2>&1; else svc wifi disable >/dev/null 2>&1; fi
        if [ "${'$'}BT" = "1" ]; then svc bluetooth enable >/dev/null 2>&1; else svc bluetooth disable >/dev/null 2>&1; fi
        if [ "${'$'}DATA" = "1" ]; then svc data enable >/dev/null 2>&1; else svc data disable >/dev/null 2>&1; fi

        rm -f "${'$'}STATE_FILE"
        echo "OK"
    """.trimIndent()

    companion object {
        private const val STATE_FILE = "/data/local/tmp/com.example.app.video_mode.state"

        // Минимальный фундамент. Телефония намеренно НЕ включена:
        // com.android.phone / IMS / Unisoc telephony будут заморожены.
        private const val CORE_KEEP =
            "android " +
            "com.android.systemui " +
            "com.android.shell " +
            "com.android.settings " +
            "com.android.permissioncontroller " +
            "com.google.android.permissioncontroller " +
            "com.android.providers.settings " +
            "com.android.providers.media " +
            "com.google.android.providers.media.module " +
            "com.android.providers.media.module " +
            "com.android.providers.downloads " +
            "com.android.providers.downloads.ui " +
            "com.android.externalstorage " +
            "com.android.documentsui " +
            "com.google.android.documentsui " +
            "com.android.intentresolver " +
            "android.intentresolver " +
            "com.android.networkstack " +
            "com.android.networkstack.tethering " +
            "com.android.captiveportallogin " +
            "com.google.android.webview " +
            "com.android.inputmethod.latin " +
            "com.android.mtp " +
            "com.android.providers.downloads.ui"
    }
}
