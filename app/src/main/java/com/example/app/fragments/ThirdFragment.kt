@file:Suppress("SameParameterValue", "SpellCheckingInspection")

package com.example.app.fragments

import com.example.app.crypto.GpgDecryptor
import com.example.app.download.DownloadHelper
import android.os.Bundle
import android.os.Environment
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.app.R
import com.example.app.shell.AdbShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.eclipse.jgit.api.Git

class ThirdFragment : Fragment() {

    private lateinit var downloadHelper: DownloadHelper

    private lateinit var editTextPasswordgnucash: EditText
    private lateinit var editTextPasswordMyPhoneConf: EditText


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_third, container, false)
        downloadHelper = DownloadHelper(requireContext())
        editTextPasswordgnucash = view.findViewById(R.id.editTextPasswordgnucash)
        editTextPasswordMyPhoneConf = view.findViewById(R.id.editTextPasswordmyphoneconf)
        setupInstallButton(view)
        setupDownloadMyPhoneConfButton(view)
        decryptMyPhoneConfButton(view)
        setupDownloadNoteButton(view)
        setupDownloadToxButton(view)
        setupGitCloneButton(view)
        decryptGnucasgpgpButton(view)
        rebootButton(view)
        powerofButton(view)
        deletedefinitlygnucahButton(view)
        setupSelfUninstallButton(view)
        return view
    }

    override fun onDestroyView() {
        downloadHelper.cleanup()
        super.onDestroyView()
    }

    private fun setupInstallButton(view: View) {
        val installButton = view.findViewById<Button>(R.id.downloadgnucashgpg)
        installButton.setOnClickListener {
            val apkUrl1 = "https://github.com/xinitronix/gnucash/raw/refs/heads/main/definitly.gnucash.gpg"
            downloadHelper.downloadToPublic(apkUrl1)

        }
    }

    private fun setupSelfUninstallButton(view: View) {
        view.findViewById<Button>(R.id.self_uninstall).setOnClickListener {
            val packageUri = Uri.parse("package:${requireContext().packageName}")
            val intent = Intent(Intent.ACTION_DELETE, packageUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            runCatching {
                startActivity(intent)
            }.onFailure {
                showToast("Не удалось открыть удаление приложения")
            }
        }
    }

    private fun deletedefinitlygnucahButton(view:View) {

        val deleteDefinitlygnucahButton = view.findViewById<Button>(R.id.deletegnucashgpg)
        deleteDefinitlygnucahButton.setOnClickListener {

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

// Определяем файлы для удаления
            val firstFile = downloadsDir.resolve("definitly.gnucash")
            val secondFile = downloadsDir.resolve("definitly.gnucash.gpg") // второй файл




// Удаляем первый файл
            if (firstFile.exists()) {
                firstFile.delete()

                showToast("Файл definitly.gnucash  успешно удалён! ")
            } else {
                showToast("Файл  definitly.gnucash не найден.")
            }

// Удаляем второй файл
            if (secondFile.exists()) {
                secondFile.delete()
                showToast("Файл definitly.gnucash.gpg  успешно удалён! ")

            } else {
                showToast("Файл definitly.gnucash.gpg  не найден ! ")
            }
        }
    }


    private fun decryptGnucasgpgpButton(view: View) {
        view.findViewById<Button>(R.id.decryptgnucashgpg).setOnClickListener {
            val password = editTextPasswordgnucash.text.toString()
            if (password.isBlank()) {
                showToast("Пароль не введен. Пожалуйста, введите пароль.")
                return@setOnClickListener
            }

            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val input = File(downloads, "definitly.gnucash.gpg")
            val output = File(downloads, "definitly.gnucash")

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    val chars = password.toCharArray()
                    try {
                        GpgDecryptor().decrypt(input, output, chars)
                        true
                    } catch (_: Exception) {
                        output.delete()
                        false
                    } finally {
                        chars.fill('\u0000')
                    }
                }

                showToast(
                    if (success) {
                        "Файл успешно расшифрован."
                    } else {
                        "Ошибка при расшифровке файла."
                    }
                )
            }
        }
    }

    private fun setupDownloadMyPhoneConfButton(view: View) {
        view.findViewById<Button>(R.id.downloadmyphoneconf).setOnClickListener {
            // raw-ссылка: ссылка /blob/ отдаёт HTML-страницу, а не сам файл
            val url = "https://github.com/definitly486/BlackviewActive5/raw/refs/heads/main/my_phone.conf.gpg"
            downloadHelper.downloadToPublic(url)
        }
    }

    private fun decryptMyPhoneConfButton(view: View) {
        view.findViewById<Button>(R.id.decryptmyphoneconf).setOnClickListener {
            val password = editTextPasswordMyPhoneConf.text.toString()
            if (password.isBlank()) {
                showToast("Пароль не введен. Пожалуйста, введите пароль.")
                return@setOnClickListener
            }

            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val input = File(downloads, "my_phone.conf.gpg")
            val output = File(downloads, "my_phone.conf")

            if (!input.exists()) {
                showToast("Файл my_phone.conf.gpg не найден. Сначала скачайте его.")
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    val chars = password.toCharArray()
                    try {
                        GpgDecryptor().decrypt(input, output, chars)
                        true
                    } catch (_: Exception) {
                        output.delete()
                        false
                    } finally {
                        chars.fill('\u0000')
                    }
                }

                showToast(
                    if (success) {
                        "Файл my_phone.conf успешно расшифрован."
                    } else {
                        "Ошибка при расшифровке файла (неверный пароль?)."
                    }
                )
            }
        }
    }

    private fun setupDownloadNoteButton(view: View) {
        val downloadnote = view.findViewById<Button>(R.id.downloadnote)
        downloadnote.setOnClickListener {
            val apkUrl1 = "https://raw.githubusercontent.com/definitly486/definitly486/refs/heads/main/note"
            downloadHelper.downloadToPublic(apkUrl1)
        }
    }

    private fun setupDownloadToxButton(view: View) {
        val downloadtox = view.findViewById<Button>(R.id.downloadtoxenc)
        downloadtox.setOnClickListener {
            val apkUrl1 = "https://github.com/definitly486/definitly486/releases/download/tox/profile.tox.enc"
            downloadHelper.downloadToPublic(apkUrl1)
        }
    }


    private fun setupGitCloneButton(view: View) {
        val cloneButton = view.findViewById<Button>(R.id.gitclonedcim)
        cloneButton.setOnClickListener {
            cloneDcimToDownloads()
        }
    }

    private fun cloneDcimToDownloads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showToast("Разрешите доступ ко всем файлам для записи в /storage/emulated/0/download/dcim")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                )
            }
            return
        }

        showToast("Клонирование GitHub DCIM в /storage/emulated/0/download/dcim…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val downloadDir = File("/storage/emulated/0/download/dcim")
                    if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                        error("Не удалось создать /storage/emulated/0/download")
                    }

                    // JGit выполняет настоящий git clone без Shizuku и без shell/git binary.
                    // Клонируем во временную папку, затем переносим содержимое репозитория
                    // прямо в /storage/emulated/0/download/, чтобы там не появился DCIM-main/.
                    val cloneDir = File(requireContext().cacheDir, "dcim_git_${System.currentTimeMillis()}")
                    cloneDir.deleteRecursively()
                    try {
                        Git.cloneRepository()
                            .setURI("https://github.com/definitly486/DCIM.git")
                            .setDirectory(cloneDir)
                            .call()
                            .use { }

                        copyDirectoryContents(cloneDir, downloadDir)
                    } finally {
                        cloneDir.deleteRecursively()
                    }
                }
            }

            result.onSuccess {
                showToast("Репозиторий DCIM успешно клонирован в /storage/emulated/0/download/dcim")
            }.onFailure { e ->
                showToast("Ошибка клонирования DCIM: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        source.listFiles()?.forEach { sourceItem ->
            val target = File(destination, sourceItem.name)
            if (sourceItem.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    error("Не удалось создать ${target.absolutePath}")
                }
                copyDirectoryContents(sourceItem, target)
            } else {
                sourceItem.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun rebootButton(view: View) {
        view.findViewById<Button>(R.id.reboot).setOnClickListener {
            runPowerCommand("reboot", "Перезагрузка…")
        }
    }

    private fun powerofButton(view: View) {
        view.findViewById<Button>(R.id.poweroff).setOnClickListener {
            runPowerCommand("reboot -p", "Выключение…")
        }
    }

    // Команды shell через Shizuku (без root)
    private fun runPowerCommand(command: String, message: String) {
        if (!AdbShell.isRunning() || !AdbShell.hasPermission()) {
            showToast("Shizuku не запущен или нет разрешения (вкладка «Настройка»)")
            return
        }
        showToast(message)
        AdbShell.execAsync(requireContext(), command)
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }



}
