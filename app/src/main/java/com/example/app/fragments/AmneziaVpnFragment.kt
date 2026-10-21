package com.example.app.fragments

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
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
import com.example.app.vpn.AwgVpnController
import com.example.app.vpn.AwgVpnController.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
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
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            loadConfig(uri)
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

    private fun describeConfig(uri: Uri, cfg: Config): String {
        val endpoint = cfg.getPeers().firstOrNull()?.getEndpoint()
            ?.map { it.toString() }?.orElse("не указан") ?: "не указан"
        val address = cfg.getInterface().getAddresses().joinToString()
        val dns = cfg.getInterface().getDnsServers().joinToString()
        return "Файл: ${uri.lastPathSegment ?: "config.conf"}\n" +
                "Адрес: $address\n" +
                "Сервер: $endpoint\n" +
                "DNS: $dns\n" +
                "Протокол: AmneziaWG"
    }

    private fun restoreConfigText() {
        val uri = configUri
        val cfg = config
        configText.text = if (uri != null && cfg != null) {
            describeConfig(uri, cfg)
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
                        ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
                    )
                }

                configUri = uri
                config = parsed
                configText.text = describeConfig(uri, parsed)

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
        // Конфиг переживает пересоздание Fragment (смена вкладок)
        private var sharedConfig: Config? = null
        private var sharedConfigUri: Uri? = null
    }
}
