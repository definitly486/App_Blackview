@file:Suppress("ControlFlowWithEmptyBody", "SpellCheckingInspection", "LocalVariableName")

package com.example.app.fragments

import com.example.app.download.DownloadHelper
import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import android.graphics.Canvas
import android.graphics.Color
import com.example.app.ApkAutoInstaller
import com.example.app.shell.AdbShell
import com.example.app.shell.SdcardApkInstaller
import androidx.lifecycle.lifecycleScope


@Suppress("DEPRECATION")
class SecondFragment : Fragment() {

    private val requestCodeWriteSettingsPermission = 1001
    private lateinit var downloadHelper: DownloadHelper
    private lateinit var downloadHelper2: DownloadHelper2


    /** Публичная папка Download: сюда скачивается и здесь лежит main.tar.gz. */
    fun getDownloadFolder(): File? {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.also { it.mkdirs() }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_second, container, false)

        // Инициализация DownloadHelper
        downloadHelper = DownloadHelper(requireContext())
        downloadHelper2 = DownloadHelper2(requireContext())

        // Настройка кнопок
        setupButtons(view)

        return view
    }

    private fun setupButtons(view: View) {
        // Кнопка удаления пакета
        val installSdcardApk = view.findViewById<Button>(R.id.installsdcardapk)
        installSdcardApk.setOnClickListener { installApksFromSdcard(installSdcardApk) }

        val installButton = view.findViewById<Button>(R.id.deletepkg)
        installButton.setOnClickListener { deletePkgFromFile("packages.txt") }

        //кнопка удаления пакетов gms

        val deletePKGGMS = view.findViewById<Button>(R.id.deletegmspkg)
        deletePKGGMS.setOnClickListener { deletePkgFromFile("GMSpackages") }

        // Кнопка скачивания main
        val button6 = view.findViewById<Button>(R.id.downloadmain)
        button6.setOnClickListener { downloadMain() }

        // Кнопка распаковки main
        val button7 = view.findViewById<Button>(R.id.unpackmain)
        button7.setOnClickListener { unpackMain() }

        // Кнопка скачивания  APK
        val downloadapk = view.findViewById<Button>(R.id.downloadapk)
        downloadapk.setOnClickListener { downloadAPK() }

        //Кнопка удаления main.tar.gz и main folder
        val deleteMain = view.findViewById<Button>(R.id.deletemain)
        deleteMain.setOnClickListener {  deleteMAIN(requireContext()) }

        // Кнопка установки обоев
        val setWallaper = view.findViewById<Button>(R.id.setwallpaper)
        setWallaper.setOnClickListener { setWallpaper() }

    }



    private fun deleteMAIN(context: Context) {
        // Публичный каталог Download
        val privateDownloadsDir = getDownloadFolder()

        // Проверяем, существует ли каталог
        if (privateDownloadsDir != null && privateDownloadsDir.exists()) {
            // Архив main.tar.gz
            val firstFile = privateDownloadsDir.resolve("main.tar.gz")
            if (firstFile.exists()) {
                if (firstFile.delete()) {
                    Toast.makeText(requireContext(), "Архив main.tar.gz успешно удалён!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Ошибка при удалении архива main.tar.gz.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Архив main.tar.gz не найден.", Toast.LENGTH_SHORT).show()
            }

            // Папка redmia5-main
            val folderToDelete = privateDownloadsDir.resolve("BlackviewActive5-main")
            if (folderToDelete.exists()) {
                if (deleteDirectory(folderToDelete)) {
                    Toast.makeText(requireContext(), "Папка 'BlackviewActive5-main' успешно удалена!", Toast.LENGTH_SHORT).show()

                } else {
                    Toast.makeText(requireContext(), "Ошибка при удалении папки 'BlackviewActive5-main'.", Toast.LENGTH_SHORT).show()

                }
            } else {
                Toast.makeText(requireContext(), "Папка 'BlackviewActive5-main' не найдена.", Toast.LENGTH_SHORT).show()

            }
        } else {
            Toast.makeText(requireContext(), "Приватный каталог 'Загрузки' не найден.", Toast.LENGTH_SHORT).show()

        }
    }


    fun deleteDirectory(directory: File): Boolean {
        if (!directory.exists()) return false // Проверяем существование папки

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleteDirectory(file) // Рекурсивно удаляем подпапки
            } else {
                file.delete() // Удаляем файлы
            }
        }

        return directory.delete() // Пробуем удалить основную папку
    }



    // Установка всех .apk из /sdcard/apk
    private fun installApksFromSdcard(button: Button) {
        val ctx = requireContext()
        button.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = SdcardApkInstaller.installAll(ctx) { progress ->
                Toast.makeText(ctx, progress, Toast.LENGTH_SHORT).show()
            }
            button.isEnabled = true

            when (summary.error) {
                "NEED_ALL_FILES" -> {
                    Toast.makeText(ctx, "Разрешите доступ ко всем файлам и нажмите кнопку ещё раз", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }
                "NEED_INSTALL_PERMISSION" -> {
                    Toast.makeText(ctx, "Разрешите установку из этого приложения и нажмите кнопку ещё раз", Toast.LENGTH_LONG).show()
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    )
                }
                null -> {
                    val message = buildString {
                        append("Установлено: ${summary.installed.size} из ${summary.total}")
                        if (summary.failed.isNotEmpty()) {
                            append("\n\nНе удалось:\n")
                            append(summary.failed.joinToString("\n") { "• $it" })
                        }
                    }
                    AlertDialog.Builder(ctx)
                        .setTitle("Установка APK")
                        .setMessage(message)
                        .setPositiveButton("OK") { d, _ -> d.dismiss() }
                        .show()
                }
                else -> Toast.makeText(ctx, summary.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAPK() {
        CoroutineScope(Dispatchers.Main).launch {
            launchLoadSequence()
        }
    }

    private fun setWallpaper (){

        val externalFilesDir = context?.getExternalFilesDir(null)
        val imagePath = File(externalFilesDir, "black_image.png").absolutePath
        setWallpaper(imagePath)

    }

    fun createBlackPngInExternalFiles(
        context: Context,
        width: Int,
        height: Int,
        fileName: String
    ): File? {
        // Получаем путь: /storage/emulated/0/Android/data/ваш.пакет/files/
        val externalFilesDir = context.getExternalFilesDir(null)
            ?: return null

        val targetFile = File(externalFilesDir, fileName).apply {
            parentFile?.mkdirs() // создаём подпапки, если нужно
        }

        var bitmap: Bitmap? = null
        var fos: FileOutputStream? = null

        try {
            // Создаём bitmap и заливаем чёрным
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)

            // Сохраняем как PNG
            fos = FileOutputStream(targetFile)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()

            println("Чёрный PNG успешно создан: ${targetFile.absolutePath}")
            return targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            bitmap?.recycle()
            fos?.close()
        }
    }


    suspend fun launchLoadSequence() {
        val urls = listOf(
            "https://github.com/definitly486/redmia5/releases/download/apk/Total_Commander_v.3.50d.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/AmneziaVPN_5.0.3.0_android11+_arm64-v8a.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/app-armeabi-v7a-fdroid-release.apk",
            "https://github.com/definitly486/Lenovo_Tab_3_7_TB3-730X/releases/download/apk/Pluma_.private_fast.browser_1.80_APKPure.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/app-release.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/Clock_2.32-release.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/dialer-fdroid-release.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/Just.Player.v0.12.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/messages-23-foss-release.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/net.sourceforge.opencamera_96.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/Yandex_Maps_17.2.0.apk",
            "https://github.com/definitly486/BlackviewActive5/releases/download/apk/YTDLnis-1.9.0-armeabi-v7a-github-release.apk"
        )

        urls.forEachIndexed { index, url ->
            val result = downloadSingleAPK(url)
            handleResult(result, index + 1)
        }
        Log.d("DownloadSequence", "Все файлы обработаны, запускаю установку")
        val summary = ApkAutoInstaller.installAutoAPK(context)
        showInstallSummary(summary)
    }

    /** Итог установки + запрос недостающих разрешений. */
    private fun showInstallSummary(summary: ApkAutoInstaller.Summary) {
        val ctx = context?.applicationContext ?: return
        when (summary.error) {
            ApkAutoInstaller.ERROR_NEED_INSTALL_PERMISSION -> {
                Toast.makeText(ctx, "Разрешите установку из этого приложения и нажмите кнопку ещё раз", Toast.LENGTH_LONG).show()
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            ApkAutoInstaller.ERROR_NEED_ALL_FILES -> {
                Toast.makeText(ctx, "Разрешите доступ ко всем файлам и нажмите кнопку ещё раз", Toast.LENGTH_LONG).show()
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            null -> {
                val mode = if (summary.viaShizuku) "Shizuku" else "системный установщик"
                val text = buildString {
                    append("Установлено: ${summary.installed}, уже были: ${summary.skipped} ($mode)")
                    if (summary.failed.isNotEmpty()) {
                        append("\nНе удалось: ${summary.failed.joinToString()}")
                    }
                }
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(ctx, summary.error, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun downloadSingleAPK(url: String): File? {
        return suspendCancellableCoroutine { continuation ->
            downloadHelper.downloadApkToPublicApkFolder(url) { file ->
                continuation.resumeWith(Result.success(file))
            }
        }
    }

    fun handleResult(file: File?, index: Int) {
        val ctx = context ?: return
        if (file != null) {
            Toast.makeText(
                ctx,
                "Файл №$index загружен: ${file.name}",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(ctx, "Ошибка загрузки файла №$index", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun downloadMain() {
        val folder = getDownloadFolder()
        if (folder == null) {
            Toast.makeText(requireContext(), "Нет доступа к папке Download", Toast.LENGTH_SHORT).show()
            return
        }

        // Если архив уже скачан — повторно не загружаем
        val tarGzFile = File(folder, "main.tar.gz")
        if (tarGzFile.exists()) {
            Toast.makeText(requireContext(), "Файл main.tar.gz уже существует", Toast.LENGTH_SHORT).show()
            return
        }

        downloadHelper.downloadToPublic("https://github.com/definitly486/BlackviewActive5/archive/main.tar.gz")
    }

    private fun setWallpaper(imagePath: String) {

        val tag = "WallpaperService"


        createBlackPngInExternalFiles(
            context = requireContext(),
            width = 1080,
            height = 1080,
            fileName = "black_image.png"
        )



        try {
            val file = File(imagePath)

            // Log the file details
            Log.d(tag, "Image Path: $imagePath")
            Log.d(tag, "File exists: ${file.exists()} and is readable: ${file.canRead()}")

            if (!file.exists()) {
                Log.e(tag, "File does not exist at $imagePath")
                return
            }



            // Decode the image with options for larger images
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imagePath, options)

            // Calculate inSampleSize if needed
            val sampleSize = calculateInSampleSize(options, 100, 100)
            options.inSampleSize = sampleSize
            options.inJustDecodeBounds = false

            val bitmap: Bitmap? = BitmapFactory.decodeFile(imagePath, options)

            if (bitmap == null) {
                Log.e(tag, "Failed to decode bitmap from $imagePath. The file might be corrupted or in an unsupported format.")
                return
            }

            val wallpaperManager = WallpaperManager.getInstance(requireContext())
            wallpaperManager.setBitmap(bitmap)

            Log.i(tag, "Wallpaper set successfully")
        } catch (e: IOException) {
            Log.e(tag, "IOException while setting wallpaper: ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            Log.e(tag, "Unexpected error while setting wallpaper: ${e.message}")
            e.printStackTrace()
        }
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun installAPK() {
        val urls = listOf(
            "Total_Commander_v.3.50d.apk",
            "k9mail-13.0.apk",
            "Google+Authenticator+7.0.apk",
            "Pluma_.private_fast.browser_1.80_APKPure.apk",
            "com.aurora.store_70.apk",
            "ByeByeDPI-arm64-v8a-release.apk",
            "Telegram+X+0.27.5.1747-arm64-v8a.apk",
            "Core+Music+Player_1.0.apk"
        )


        for (url in urls) {
            context?.getExternalFilesDir("APK")?.also { it.mkdirs() }

            downloadHelper.installApkFromApkFolder(url)
        }

    }


    fun setScreenBrightness(context: Context, brightnessValue: Int) {
        if (brightnessValue in 0..1000) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
        }
    }


    private fun unpackMain() {
        val folder = getDownloadFolder() ?: return
        val tarGzFile = File(folder, "main.tar.gz")
        val outputDir = File(folder, "")
        if (!tarGzFile.exists()) {
            Toast.makeText(requireContext(), "Файл main.tar.gz не существует", Toast.LENGTH_SHORT).show()
            return
        }
        downloadHelper2 = DownloadHelper2(requireContext())
        downloadHelper2.decompressTarGz(tarGzFile, outputDir)
    }





    private fun handleDownloadResult(file: File?, name: String) {
        if (file != null) {
            Toast.makeText(requireContext(), "Файл загружен: ${file.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Ошибка загрузки файла $name", Toast.LENGTH_SHORT).show()
        }
    }


    @Suppress("PrivatePropertyName")
    private val TAG = "PkgDeleter"  // Твоя метка для фильтрации в Logcat

    fun Fragment.deletePkgFromFile(fileName: String) {
        if (!AdbShell.isRunning() || !AdbShell.hasPermission()) {
            showShizukuRequiredDialog(requireContext())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Начинается удаление пакетов...", Toast.LENGTH_SHORT).show()
            }

            Log.i(TAG, "Начало массового удаления из файла: $fileName")

            val appPrivateDirectory = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: run {
                    val error = "Не удалось получить папку для файлов"
                    Log.e(TAG, error)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

            val file = File(appPrivateDirectory, fileName)
            if (!file.exists()) {
                Log.w(TAG, "Файл не найден: $fileName")
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Файл не найден: $fileName", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            Log.i(TAG, "Найден файл: ${file.absolutePath}, строк: ${file.readLines().size}")

            var processedCount = 0
            var successCount = 0
            var skippedCount = 0

            try {
                val lines = file.readLines()

                for ((index, rawLine) in lines.withIndex()) {
                    val packageName = rawLine.trim()
                    if (packageName.isBlank()) {
                        Log.v(TAG, "Пропущена пустая строка #${index + 1}")
                        continue
                    }

                    processedCount++
                    Log.i(TAG, "Обработка [$processedCount/${lines.size}]: $packageName")

                    // Проверка установки
                    val isInstalled = withContext(Dispatchers.Main) {
                        isPackageInstalled(packageName)
                    }

                    if (!isInstalled) {
                        Log.w(TAG, "Пропущен (не установлен): $packageName")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Пропущен: $packageName", Toast.LENGTH_SHORT).show()
                        }
                        skippedCount++
                        continue
                    }

                    // Удаление
                    val deleted = AdbShell.exec(
                        requireContext().applicationContext,
                        "pm uninstall --user 0 $packageName"
                    ).ok

                    if (deleted) {
                        Log.i(TAG, "УСПЕШНО удалён: $packageName")
                        successCount++
                    } else {
                        Log.e(TAG, "ОШИБКА при удалении: $packageName")
                    }

                    delay(500) // Пауза для стабильности
                }

                // Итог
                val summary = "Завершено! Обработано: $processedCount | Удалено: $successCount | Пропущено: $skippedCount"
                Log.i(TAG, summary)

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show()
                    showCompletionDialog(requireContext())
                    createReloadDialog()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Критическая ошибка при обработке файла", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Проверка установки пакета
    private fun Fragment.isPackageInstalled(packageName: String): Boolean {
        val pm = requireContext().packageManager
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    // API 33+: используем MATCH_ALL через флаги
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                    true
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> { // API 30+
                    // На API 30–32 используем новый метод с int-флагами
                    pm.getPackageInfo(packageName, PackageManager.MATCH_ALL)
                    true
                }
                else -> {
                    // До API 30: старый способ (депрекейтед, но работает)
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
                    // Или просто 0 — но с 0 системные тоже могут не находиться на некоторых устройствах
                    true
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при проверке пакета $packageName", e)
            false
        }
    }



    fun showShizukuRequiredDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("Нужен Shizuku")
            .setMessage("Удаление пакетов работает только через Shizuku. Установите и запустите его на вкладке «Настройка».")
            .setPositiveButton("Продолжить") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    fun showCompletionDialog(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Удаление завершено")
        builder.setMessage("Все выбранные пакеты успешно удалены.")
        builder.setPositiveButton("Продолжить") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }


    fun createReloadDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())

        // Заголовок диалога
        alertBuilder.setTitle("Подтверждение перезагрузки")

        // Сообщение в диалоговом окне
        alertBuilder.setMessage("Вы действительно хотите перезагрузить устройство?")

        // Положительная кнопка (перезагружаем устройство)
        alertBuilder.setPositiveButton("Да") { _: DialogInterface, _: Int ->
            // Перезагрузка командой shell через Shizuku (без root)
            AdbShell.execAsync(requireContext(), "reboot") }

        // Отрицательная кнопка (закрываем диалог)
        alertBuilder.setNegativeButton("Нет") { dialog: DialogInterface, _: Int ->
            dialog.cancel()
        }

        // Показываем диалог
        val dialog = alertBuilder.create()
        dialog.show()
    }




}