package com.example.app.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.app.R
import com.example.app.vpn.AwgConfigNormalizer
import com.example.app.vpn.AwgVpnController
import com.example.app.vpn.AwgVpnController.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * UI вкладки AmneziaWG. Вся логика туннеля (запуск, ожидание handshake, перезапуск при смене сети,
 * watchdog) живёт в [AwgVpnController], который переживает пересоздание Fragment при смене вкладок.
 */
class AmneziaVpnFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var configText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button

    private var config: Config?
        get() = sharedConfig
        set(value) { sharedConfig = value }

    private var configUri: Uri?
        get() = sharedConfigUri
        set(value) { sharedConfigUri = value }

    private val vpnPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                connectInternal()
            } else {
                Toast.makeText(requireContext(), "VPN permission was denied", Toast.LENGTH_SHORT).show()
            }
        }

    private val filePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            loadConfig(uri)
        }

    private val prefs by lazy {
        requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    private val savedConfigFile: File
        get() = File(requireContext().filesDir, SAVED_CONFIG_FILE)

    private fun displayNameOf(uri: Uri): String? =
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

    /** Если после перезапуска приложения конфиг в памяти пуст — поднимаем сохранённый. */
    private fun restoreSavedConfig() {
        if (config != null) return
        val file = savedConfigFile
        if (!file.exists()) return
        try {
            val parsed = Config.parse(
                ByteArrayInputStream(
                    AwgConfigNormalizer.normalize(file.readText(StandardCharsets.UTF_8))
                        .toByteArray(StandardCharsets.UTF_8)
                )
            )
            val name = prefs.getString(KEY_CONFIG_NAME, null) ?: "config.conf"
            config = parsed
            configUri = Uri.fromFile(file)
            configText.text = describeConfig(name, parsed)
        } catch (_: Exception) {
            // повреждённый файл — просто просим выбрать заново
            file.delete()
            prefs.edit().remove(KEY_CONFIG_NAME).apply()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_amnezia_vpn, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusText = view.findViewById(R.id.amneziaStatus)
        configText = view.findViewById(R.id.amneziaConfig)
        connectButton = view.findViewById(R.id.amneziaConnect)
        disconnectButton = view.findViewById(R.id.amneziaDisconnect)

        AwgVpnController.init(requireContext())

        view.findViewById<Button>(R.id.amneziaOpen).setOnClickListener {
            filePicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }

        connectButton.setOnClickListener {
            if (config == null) {
                Toast.makeText(requireContext(), "Сначала откройте .conf", Toast.LENGTH_SHORT).show()
            } else {
                requestVpnPermissionAndConnect()
            }
        }

        disconnectButton.setOnClickListener {
            AwgVpnController.disconnect()
        }

        restoreSavedConfig()
        restoreConfigText()

        // Статус и ошибки приходят из контроллера, пока вкладка видна.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AwgVpnController.status.collect { render(it) }
                }
                launch {
                    AwgVpnController.errors.collect { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun render(status: AwgVpnController.Status) {
        val detail = if (status.detail.isNotEmpty()) " (${status.detail})" else ""
        when (status.phase) {
            Phase.DISCONNECTED -> showStatus("● VPN отключён", false, R.color.status_off)
            Phase.CONNECTING -> showStatus("● VPN подключается…$detail", true, R.color.status_busy)
            Phase.WAITING_NETWORK -> showStatus("● Ожидание сети…", true, R.color.status_busy)
            Phase.CONNECTED -> showStatus("● VPN подключён", true, R.color.status_on)
        }
    }

    private fun showStatus(text: String, active: Boolean, colorRes: Int) {
        if (!::statusText.isInitialized) return
        statusText.text = text
        statusText.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), colorRes))
        connectButton.isEnabled = !active && config != null
        disconnectButton.isEnabled = active
    }

    private fun describeConfig(name: String, cfg: Config): String {
        val endpoint = cfg.getPeers().firstOrNull()?.getEndpoint()
            ?.map { it.toString() }?.orElse("не указан") ?: "не указан"
        val address = cfg.getInterface().getAddresses().joinToString()
        val dns = cfg.getInterface().getDnsServers().joinToString()
        return "Файл: $name\n" +
                "Адрес: $address\n" +
                "Сервер: $endpoint\n" +
                "DNS: $dns\n" +
                "Протокол: AmneziaWG"
    }

    private fun restoreConfigText() {
        val cfg = config
        configText.text = if (cfg != null) {
            describeConfig(prefs.getString(KEY_CONFIG_NAME, null) ?: "config.conf", cfg)
        } else {
            "Конфигурация не выбрана"
        }
    }

    private fun loadConfig(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)!!.use {
                        it.readBytes().toString(StandardCharsets.UTF_8)
                    }
                }

                val parsed = withContext(Dispatchers.Default) {
                    Config.parse(
                        ByteArrayInputStream(
                            AwgConfigNormalizer.normalize(text).toByteArray(StandardCharsets.UTF_8)
                        )
                    )
                }

                // Парсинг прошёл — сохраняем копию в приватное хранилище
                val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "config.conf"
                withContext(Dispatchers.IO) {
                    savedConfigFile.writeText(text, StandardCharsets.UTF_8)
                }
                prefs.edit().putString(KEY_CONFIG_NAME, name).apply()

                configUri = uri
                config = parsed
                configText.text = describeConfig(name, parsed)

                Toast.makeText(requireContext(), "Конфигурация загружена", Toast.LENGTH_SHORT).show()
                render(AwgVpnController.status.value)
            } catch (e: Exception) {
                config = null
                showStatus("● Ошибка конфигурации", false, R.color.status_off)
                Toast.makeText(
                    requireContext(),
                    "Не удалось прочитать конфиг: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(requireContext())
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            connectInternal()
        }
    }

    private fun connectInternal() {
        val cfg = config ?: return
        AwgVpnController.connect(cfg)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Не отключаем VPN при уничтожении Fragment: туннелем управляет AwgVpnController,
        // он работает, пока пользователь явно не нажмёт «Отключить».
    }

    companion object {
        private const val PREFS_NAME = "amnezia_vpn"
        private const val KEY_CONFIG_NAME = "config_name"
        private const val SAVED_CONFIG_FILE = "amnezia_saved.conf"

        // Конфиг переживает пересоздание Fragment (смена вкладок)
        private var sharedConfig: Config? = null
        private var sharedConfigUri: Uri? = null
    }
}
