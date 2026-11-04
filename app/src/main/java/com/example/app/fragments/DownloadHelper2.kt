@file:Suppress("SameParameterValue", "SpellCheckingInspection")

package com.example.app.fragments

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStreamReader


@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class DownloadHelper2(private val context: Context) {
    val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    /** Распаковывает архив и возвращает количество распакованных файлов. */
    fun decompressTarGz(tarGzFile: File, outputDir: File): Int {
        if (!tarGzFile.isFile) {
            throw FileNotFoundException("File not found: ${tarGzFile.path}")
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw java.io.IOException("Cannot create output directory: ${outputDir.path}")
        }
        if (!outputDir.isDirectory || !outputDir.canWrite()) {
            throw java.io.IOException("Output directory is not writable: ${outputDir.path}")
        }

        val canonicalOutputDir = outputDir.canonicalFile
        val outputRoot = canonicalOutputDir.toPath()
        var filesCount = 0

        GzipCompressorInputStream(BufferedInputStream(FileInputStream(tarGzFile))).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                generateSequence { tarIn.nextEntry }.forEach { entry ->
                    val outputFile = File(canonicalOutputDir, entry.name).canonicalFile
                    if (!outputFile.toPath().startsWith(outputRoot)) {
                        throw SecurityException("Недопустимый путь в архиве: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        if (!outputFile.exists() && !outputFile.mkdirs()) {
                            throw java.io.IOException("Cannot create directory: ${outputFile.path}")
                        }
                    } else {
                        val parent = outputFile.parentFile
                            ?: throw java.io.IOException("Invalid archive path: ${entry.name}")
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw java.io.IOException("Cannot create directory: ${parent.path}")
                        }
                        outputFile.outputStream().use { outStream ->
                            tarIn.copyTo(outStream)
                        }
                        filesCount++
                    }
                }
            }
        }
        return filesCount
    }


    fun unpackTarXz(tarXzFile: File, outputDirectory: File) {
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }

        FileInputStream(tarXzFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                XZCompressorInputStream(bis).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        var entry = tarIn.nextEntry
                        while (entry != null) {
                            val outputFile = File(outputDirectory, entry.name)
                            if (entry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                FileOutputStream(outputFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                }
            }
        }

    }
}
