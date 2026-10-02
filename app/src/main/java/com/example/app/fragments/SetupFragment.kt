package com.example.app.fragments

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.app.R
import com.example.app.pairing.PairingAutomation
import com.example.app.shell.AdbShell
import com.example.app.shell.BlockPermissions
import com.example.app.shell.DeviceSetup
import com.example.app.shell.WifiConnector
import com.example.app.shell.ShizukuInstaller
import kotlinx.coroutines.launch

/**
 * Вкладка «Настройка»: одна кнопка выполняет набор команд «adb shell» на самом устройстве
 * (через Shizuku, без root).
 */
class SetupFragment : Fragment(R.layout.fragment_setup) {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnRun: Button
    private lateinit var btnInstall: Button
    private lateinit var btnBlock: Button
    private lateinit var btnUninstall: Button
    private lateinit var btnPair: Button
    private lateinit var btnUnlockDev: Button
    private lateinit var btnUsbDebug: Button
    private lateinit var btnUsbDebugOff: Button

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        status = view.findViewById(R.id.setupStatus)
        log = view.findViewById(R.id.setupLog)
        scroll = view.findViewById(R.id.setupScroll)
        btnRun = view.findViewById(R.id.btnRunSetup)
        btnInstall = view.findViewById(R.id.btnInstallShizuku)
        btnBlock = view.findViewById(R.id.btnBlockPermissions)

        btnUninstall = view.findViewById(R.id.btnUninstallShizuku)
        btnPair = view.findViewById(R.id.btnAutoPair)

        btnUnlockDev = view.findViewById(R.id.btnUnlockDev)
        btnUsbDebug = view.findViewById(R.id.btnUsbDebug)
        btnUsbDebugOff = view.findViewById(R.id.btnUsbDebugOff)

        btnPair.setOnClickListener { onPairClicked() }
        btnUnlockDev.setOnClickListener { onUnlockDevClicked() }
        btnUsbDebug.setOnClickListener { onUsbDebugClicked() }
        btnUsbDebugOff.setOnClickListener { onUsbDebugOffClicked() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PairingAutomation.lines.collect { lines ->
                        if (lines.isNotEmpty()) {
                            log.text = lines.joinToString("\n") + "\n"
                            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                }
                launch {
                    PairingAutomation.task.collect { task ->
                        btnPair.text = if (task == PairingAutomation.Task.PAIR)
                            "Остановить сопряжение" else "Сопряжение (авто)"
                        btnUnlockDev.text = if (task == PairingAutomation.Task.DEV_UNLOCK)
                            "Остановить" else "Разблокировать меню разработчика"
                        btnUsbDebug.text = if (task == PairingAutomation.Task.USB_DEBUG)
                            "Остановить" else "Включить отладку по USB"
                        btnPair.isEnabled = task == null || task == PairingAutomation.Task.PAIR
                        btnUnlockDev.isEnabled = task == null || task == PairingAutomation.Task.DEV_UNLOCK
                        btnUsbDebug.isEnabled = task == null || task == PairingAutomation.Task.USB_DEBUG
                        btnUsbDebugOff.text = if (task == PairingAutomation.Task.USB_DEBUG_OFF)
                            "Остановить" else "Выключить отладку по USB"
                        btnUsbDebugOff.isEnabled = task == null || task == PairingAutomation.Task.USB_DEBUG_OFF
                        if (task == null) refreshStatus()
                    }
                }
            }
        }

        btnInstall.setOnClickListener { installShizuku() }
        btnUninstall.setOnClickListener { confirmUninstallShizuku() }

        view.findViewById<Button>(R.id.btnConnectWifi).setOnClickListener {
            val context = requireContext().applicationContext
            val result = WifiConnector.connect(
                context, DeviceSetup.WIFI_SSID, DeviceSetup.WIFI_PASSWORD
            )

            append((if (result.ok) "✓ " else "✗ ") + result.message)

            if (result.ok) {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (AdbShell.isRunning() && AdbShell.hasPermission()) {
                        append("↻ Перезапуск Wi-Fi для применения настроек...")

                        val disable = AdbShell.exec(context, "cmd wifi set-wifi-enabled disabled")
                        kotlinx.coroutines.delay(1000)
                        val enable = AdbShell.exec(context, "cmd wifi set-wifi-enabled enabled")

                        if (disable.ok && enable.ok) {
                            append("✓ Wi-Fi перезапущен, настройки применены")
                            Toast.makeText(
                                context,
                                "Wi-Fi перезапущен, настройки применены",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            append("✗ Не удалось автоматически перезапустить Wi-Fi")
                            Toast.makeText(
                                context,
                                "Сеть добавлена, но Wi-Fi не удалось перезапустить автоматически",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            result.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }


        btnRun.setOnClickListener { runSteps(DeviceSetup.steps) }
        btnBlock.setOnClickListener { runSteps(BlockPermissions.steps) }
        view.findViewById<Button>(R.id.btnOpenShizuku).setOnClickListener { openShizuku() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refreshStatus()
    }

    /** Одна кнопка: Shizuku → «Сопряжение» → код из настроек → уведомление → «Запустить» → «Разрешить всегда». */
    private fun onPairClicked() = startTask { PairingAutomation.start() }

    /** Одна кнопка: «О телефоне» → 7 нажатий на «Номер сборки» → меню разработчика включено. */
    private fun onUnlockDevClicked() = startTask { PairingAutomation.startDeveloperUnlock() }

    /** Одна кнопка: «Для разработчиков» → переключатель «Отладка по USB» → подтвердить диалог. */
    private fun onUsbDebugClicked() = startTask { PairingAutomation.startUsbDebug() }

    /** Одна кнопка: «Для разработчиков» → выключить переключатель «Отладка по USB». */
    private fun onUsbDebugOffClicked() = startTask { PairingAutomation.startUsbDebugOff() }

    private fun startTask(start: () -> PairingAutomation.StartResult) {
        if (PairingAutomation.running.value) {
            PairingAutomation.stop()
            return
        }
        when (start()) {
            PairingAutomation.StartResult.STARTED -> log.text = ""
            PairingAutomation.StartResult.ALREADY_RUNNING -> Unit
            PairingAutomation.StartResult.SERVICE_DISABLED ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Включите службу доступности")
                    .setMessage(
                        "Один раз включите «App Blackview — сопряжение Shizuku» в Специальных возможностях " +
                            "(Установленные службы), затем снова нажмите нужную кнопку.\n\n" +
                            "Если пункт недоступен: Настройки → Приложения → App Blackview → ⋮ → " +
                            "«Разрешить ограниченные настройки»."
                    )
                    .setPositiveButton("Открыть") { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
        }
    }

    private fun installShizuku() {
        when (val r = ShizukuInstaller.install(requireContext())) {
            is ShizukuInstaller.Result.Started -> Unit
            is ShizukuInstaller.Result.NeedInstallPermission ->
                Toast.makeText(requireContext(), "Разрешите установку из этого приложения и нажмите кнопку ещё раз", Toast.LENGTH_LONG).show()
            is ShizukuInstaller.Result.Error ->
                Toast.makeText(requireContext(), "Ошибка: ${r.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmUninstallShizuku() {
        AlertDialog.Builder(requireContext())
            .setTitle("Удалить Shizuku?")
            .setMessage("После удаления команды с правами shell перестанут работать, пока Shizuku не будет установлен и запущен снова.")
            .setPositiveButton("Удалить") { _, _ ->
                val r = ShizukuInstaller.uninstall(requireContext())
                if (r is ShizukuInstaller.Result.Error) {
                    Toast.makeText(requireContext(), "Ошибка: ${r.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshStatus() {
        val installed = ShizukuInstaller.isInstalled(requireContext())
        btnUninstall.isEnabled = installed
        btnInstall.text = if (installed) "Переустановить Shizuku" else "Установить Shizuku"
        status.text = when {
            !installed ->
                "Shizuku не установлен. Нажмите «Установить Shizuku» (APK вшит в приложение)."
            !AdbShell.isRunning() ->
                "Shizuku установлен, но не запущен. Откройте его и запустите через Wireless debugging («Start via wireless debugging»)."
            !AdbShell.hasPermission() ->
                "Shizuku работает. При нажатии на кнопку будет запрошено разрешение."
            else -> "Shizuku готов. Команды выполняются с правами shell."
        }
    }

    private fun runSteps(steps: List<DeviceSetup.Step>) {
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            if (!AdbShell.isRunning()) {
                refreshStatus()
                Toast.makeText(appContext, "Сначала запустите Shizuku", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!AdbShell.hasPermission() && !AdbShell.requestPermission()) {
                refreshStatus()
                Toast.makeText(appContext, "Разрешение Shizuku не выдано", Toast.LENGTH_LONG).show()
                return@launch
            }
            refreshStatus()

            btnRun.isEnabled = false
            btnBlock.isEnabled = false
            log.text = ""

            var okCount = 0
            steps.forEachIndexed { index, step ->
                val result = AdbShell.exec(appContext, step.command)
                if (result.ok) okCount++

                val mark = if (result.ok) "✓" else "✗"
                val details = if (!result.ok && result.output.isNotBlank()) {
                    "\n     " + result.output.lineSequence().first().take(140)
                } else ""
                append("$mark ${index + 1}/${steps.size} ${step.label}$details")
            }

            append("\nГотово: $okCount из ${steps.size}")
            btnRun.isEnabled = true
            btnBlock.isEnabled = true
        }
    }

    private fun append(line: String) {
        log.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openShizuku() {
        val ctx = requireContext()

        if (!ShizukuInstaller.isInstalled(ctx)) {
            AlertDialog.Builder(ctx)
                .setTitle("Shizuku не установлен")
                .setMessage("Сначала нужно установить Shizuku (APK вшит в приложение). Установить сейчас?")
                .setPositiveButton("Установить") { _, _ -> installShizuku() }
                .setNegativeButton("Отмена", null)
                .show()
            refreshStatus()
            return
        }

        val intent = ctx.packageManager.getLaunchIntentForPackage(ShizukuInstaller.PACKAGE)
        if (intent == null) {
            Toast.makeText(ctx, "У Shizuku нет окна запуска", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось открыть Shizuku", Toast.LENGTH_SHORT).show()
        }
    }
}
