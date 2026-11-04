@file:Suppress("SameParameterValue", "SpellCheckingInspection")

package com.example.app.fragments

import com.example.app.crypto.GpgDecryptor
import com.example.app.download.DownloadHelper
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.content.ContentValues
import android.app.DownloadManager
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
import java.io.IOException
import org.eclipse.jgit.api.Git
import android.util.Log
import android.webkit.MimeTypeMap

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
            if (editTextPasswordgnucash.text.isBlank()) {
                showToast("Пароль не введен. Пожалуйста, введите пароль.")
                return@setOnClickListener
            }
            decryptDownloadedGpgFile(
                inputName = "definitly.gnucash.gpg",
                outputName = "definitly.gnucash",
                password = editTextPasswordgnucash.text.toString()
            )
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
            if (editTextPasswordMyPhoneConf.text.isBlank()) {
                showToast("Пароль не введен. Пожалуйста, введите пароль.")
                return@setOnClickListener
            }
            decryptDownloadedGpgFile(
                inputName = "my_phone.conf.gpg",
                outputName = "my_phone.conf",
                password = editTextPasswordMyPhoneConf.text.toString()
            )
        }
    }

    /**
     * Находит файл, скачанный этой страницей через DownloadManager.
     * Системный SAF-выбор здесь не нужен: пользователь уже скачал файл
     * кнопкой загрузки, поэтому используем URI завершённой загрузки.
     */
    private fun findDownloadedFileUri(fileName: String): Uri? {
        val manager = requireContext().getSystemService(DownloadManager::class.java) ?: return null
        val query = DownloadManager.Query()
            .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)

        manager.query(query)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            if (idColumn < 0 || titleColumn < 0) return null

            while (cursor.moveToNext()) {
                if (cursor.getString(titleColumn) == fileName) {
                    val id = cursor.getLong(idColumn)
                    manager.getUriForDownloadedFile(id)?.let { return it }
                }
            }
        }
        return null
    }

    private fun decryptDownloadedGpgFile(
        inputName: String,
        outputName: String,
        password: String
    ) {
        if (password.isBlank()) {
            showToast("Пароль не введен. Пожалуйста, введите пароль.")
            return
        }

        val uri = findDownloadedFileUri(inputName)
        if (uri == null) {
            showToast("Файл $inputName не найден среди загрузок приложения. Сначала скачайте его.")
            return
        }

        decryptGpgUri(uri, outputName)
    }

    /**
     * Расшифровывает URI завершённой загрузки во временный файл
     * и публикует результат в Downloads через MediaStore.
     */
    private fun decryptGpgUri(uri: Uri, outputName: String) {
        val passwordText = when (outputName) {
            "definitly.gnucash" -> editTextPasswordgnucash.text.toString()
            "my_phone.conf" -> editTextPasswordMyPhoneConf.text.toString()
            else -> {
                showToast("Неизвестный тип файла")
                return
            }
        }
        if (passwordText.isBlank()) {
            showToast("Пароль не введен. Пожалуйста, введите пароль.")
            return
        }

        val password = passwordText.toCharArray()
        val ctx = requireContext().applicationContext
        showToast("Расшифровка файла…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val tmp = File(ctx.cacheDir, "gpg_${System.currentTimeMillis()}.tmp")
                try {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IOException("Не удалось открыть выбранный файл")
                    input.use { ins ->
                        tmp.outputStream().use { outs ->
                            GpgDecryptor().decrypt(ins.buffered(), outs, password)
                        }
                    }
                    saveGpgResultToDownloads(ctx, tmp, outputName)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e("GpgDecrypt", "Failed to decrypt $outputName", e)
                    Result.failure(e)
                } finally {
                    tmp.delete()
                    password.fill('\u0000')
                }
            }

            result.onSuccess {
                showToast("Файл успешно расшифрован и сохранён в Загрузки: $outputName")
            }.onFailure { e ->
                val detail = e.message?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName
                showToast("Ошибка расшифровки: \$detail")
            }
        }
    }

    private fun saveGpgResultToDownloads(
        ctx: android.content.Context,
        source: File,
        outputName: String
    ) {
        val extension = outputName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension) ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = ctx.contentResolver
        val outputUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Не удалось создать файл в папке Загрузки")

        try {
            resolver.openOutputStream(outputUri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Не удалось записать результат в папку Загрузки")

            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(outputUri, ready, null, null)
        } catch (e: Exception) {
            resolver.delete(outputUri, null, null)
            throw e
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

    private companion object {
        const val REQUEST_DCIM_STORAGE_PERMISSION = 1103
    }

    private fun hasDcimStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            requireContext().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun cloneDcimToDownloads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showToast("Разрешите доступ ко всем файлам и повторите клонирование")
                runCatching {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                return
            }
        } else if (!hasDcimStoragePermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_DCIM_STORAGE_PERMISSION
            )
            return
        }

        startDcimClone()
    }

    @Deprecated("Deprecated by Android; required for Android 10 runtime permission result")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_DCIM_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                cloneDcimToDownloads()
            } else {
                showToast("Для клонирования в Download необходимо разрешение на запись")
            }
        }
    }

    private fun startDcimClone() {
        val context = requireContext().applicationContext
        val cacheDir = requireContext().cacheDir
        val destination = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "dcim"
        )

        showToast("Клонирование GitHub DCIM…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!destination.exists() && !destination.mkdirs()) {
                        error("Не удалось создать папку: ${destination.absolutePath}")
                    }
                    if (!destination.isDirectory || !destination.canWrite()) {
                        error("Нет доступа на запись в: ${destination.absolutePath}")
                    }

                    // Сначала клонируем в приватный каталог приложения. Это исключает
                    // сбои JGit из-за Scoped Storage во время создания .git и объектов.
                    val cloneDir = File(cacheDir, "dcim_git_${System.currentTimeMillis()}")
                    try {
                        val cloneResult = com.example.app.git.GitRepositoryCloner(context)
                            .clone("https://github.com/definitly486/DCIM.git", cloneDir)
                        val cloned = cloneResult.getOrElse { throw it }
                        copyDirectoryContents(cloned, destination)
                    } finally {
                        cloneDir.deleteRecursively()
                    }
                }
            }

            result.onSuccess {
                if (isAdded) showToast("Репозиторий DCIM успешно клонирован в ${destination.absolutePath}")
            }.onFailure { e ->
                Log.e("GitCloneDCIM", "Ошибка клонирования DCIM", e)
                if (isAdded) showToast("Ошибка клонирования: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        val children = source.listFiles()
            ?: throw IOException("Не удалось прочитать каталог: ${source.absolutePath}")
        for (sourceItem in children) {
            val target = File(destination, sourceItem.name)
            if (sourceItem.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    throw IOException("Не удалось создать каталог: ${target.absolutePath}")
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
