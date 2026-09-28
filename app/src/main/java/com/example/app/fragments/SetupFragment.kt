package com.example.app.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app.R
import com.example.app.shell.AdbShell
import com.example.app.shell.DeviceSetup
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        status = view.findViewById(R.id.setupStatus)
        log = view.findViewById(R.id.setupLog)
        scroll = view.findViewById(R.id.setupScroll)
        btnRun = view.findViewById(R.id.btnRunSetup)
        btnInstall = view.findViewById(R.id.btnInstallShizuku)

        btnInstall.setOnClickListener { installShizuku() }

        btnRun.setOnClickListener { runSetup() }
        view.findViewById<Button>(R.id.btnOpenShizuku).setOnClickListener { openShizuku() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refreshStatus()
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

    private fun refreshStatus() {
        val installed = ShizukuInstaller.isInstalled(requireContext())
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

    private fun runSetup() {
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
            log.text = ""

            val steps = DeviceSetup.steps
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
        }
    }

    private fun append(line: String) {
        log.append(line + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openShizuku() {
        val pm = requireContext().packageManager
        val launch = pm.getLaunchIntentForPackage(ShizukuInstaller.PACKAGE)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Не удалось открыть Shizuku", Toast.LENGTH_SHORT).show()
        }
    }
}
