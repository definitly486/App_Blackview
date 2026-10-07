@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import com.example.app.download.DownloadHelper
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.app.shell.PackageSessionInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.fragment.app.Fragment
import com.example.app.R
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
class SeventhFragment  : Fragment()  {

    private lateinit var downloadHelper: DownloadHelper

    fun getDownloadFolder(): File? {
        return context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    }

    fun getDownloadFolder2(): File? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dir?.exists() == false) dir.mkdirs()
        return dir
    }


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_seventh, container, false)

        // Инициализация DownloadHelper
        downloadHelper = DownloadHelper(requireContext())

        // Настройка кнопок
        setupButtons(view)
        return view

    }

    private fun setupButtons(view: View) {

        // Кнопка скачивания gate
        val downloadgate = view.findViewById<Button>(R.id.download_gate)
        downloadgate.setOnClickListener { downloadGATE() }

        // Кнопка установки gate
        val installgate = view.findViewById<Button>(R.id.installgate)
        installgate.setOnClickListener { installGATE() }

        // Кнопка установки binance
        val installbinance = view.findViewById<Button>(R.id.installbinance)
        installbinance.setOnClickListener { installBINANCE() }
    }

    private fun downloadGATE(){
        downloadHelper.downloadTool("https://github.com/definitly486/redmia5/releases/download/apk/gate.base.zip","gate") { file ->
            handleDownloadResult(file, "gate")
        }
    }

    private fun installGATE(){
        unzipgate("gate.base.zip")
        val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloadHelper.installGate( "gate.apk")
    }

    private fun installBINANCE() {
        val publicDownloads = getDownloadFolder2() ?: run {
            Toast.makeText(requireContext(), "Не удалось получить папку Download", Toast.LENGTH_LONG).show()
            return
        }

        val xapkName = "com.binance.dev-100300004.xapk"
        val xapkFile = File(publicDownloads, xapkName)

        // Скачиваем именно в общедоступную Download. После завершения
        // callback автоматически распакует XAPK и передаст APK системному установщику.
        downloadHelper.downloadFileToPublic(
            url = "https://github.com/definitly486/redmia5/releases/download/apk/$xapkName",
            onComplete = { downloaded ->
                if (downloaded == null) {
                    Toast.makeText(requireContext(), "Ошибка загрузки Binance", Toast.LENGTH_LONG).show()
                    return@downloadFileToPublic
                }

                lifecycleScope.launch {
                    val apkFiles = withContext(Dispatchers.IO) {
                        unzipBinance(downloaded, publicDownloads)
                    }

                    if (apkFiles.isEmpty()) {
                        Toast.makeText(requireContext(), "В XAPK не найдены APK-файлы", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    Toast.makeText(
                        requireContext(),
                        "Распаковка завершена: ${apkFiles.size} APK. Запуск установки…",
                        Toast.LENGTH_LONG
                    ).show()

                    val result = PackageSessionInstaller.installAll(requireContext(), apkFiles)
                    val message = if (result.ok) {
                        "Binance успешно установлен"
                    } else {
                        "Ошибка установки Binance: ${result.message ?: "неизвестная ошибка"}"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    /** Распаковывает XAPK в общедоступный Download/Binance и возвращает APK-файлы. */
    private fun unzipBinance(xapkFile: File, publicDownloads: File): List<File> {
        val outputDir = File(publicDownloads, "Binance")
        if (!outputDir.exists() && !outputDir.mkdirs()) return emptyList()

        val apkFiles = mutableListOf<File>()

        return try {
            FileInputStream(xapkFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        val current = entry ?: continue
                        val dest = File(outputDir, current.name)
                        val canonicalDir = outputDir.canonicalFile
                        val canonicalDest = dest.canonicalFile

                        // Защита от ../ внутри архива.
                        if (canonicalDest != canonicalDir &&
                            !canonicalDest.path.startsWith(canonicalDir.path + File.separator)
                        ) {
                            zis.closeEntry()
                            continue
                        }

                        if (current.isDirectory) {
                            dest.mkdirs()
                        } else {
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { fos ->
                                zis.copyTo(fos)
                            }
                            if (dest.extension.equals("apk", ignoreCase = true)) {
                                apkFiles += dest
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }
            apkFiles.filter { it.isFile && it.length() > 0L }.sortedBy { it.name }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }





    private fun handleDownloadResult(file: File?, @Suppress("SameParameterValue") name: String) {
        if (file != null) {
            Toast.makeText(requireContext(), "Файл загружен: ${file.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Ошибка загрузки файла $name", Toast.LENGTH_SHORT).show()
        }
    }

    fun unzipgate(filename: String): Boolean {
        val folder = getDownloadFolder() ?: return false
        val zipFile = File(folder, filename)


        // Check if target APK already exists
        val targetApk = File(folder, "gate.apk")
        if (targetApk.exists()) {
            Toast.makeText(context, "Файл gate.apk  уже существует", Toast.LENGTH_SHORT).show()

            return true
        }

        try {
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        val destFile = File(folder, entry!!.name)
                        destFile.parentFile?.mkdirs()
                        if (!entry.isDirectory) {
                            FileOutputStream(destFile).use { fos ->
                                val buffer = ByteArray(4096)
                                var count: Int
                                while (zis.read(buffer).also { count = it } != -1) {
                                    fos.write(buffer, 0, count)
                                }
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return true
    }

}

