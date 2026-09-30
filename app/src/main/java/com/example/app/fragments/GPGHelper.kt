@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.jcajce.*
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.Security
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder

class GPGHelper {


//функция расшифровки с использованием библиотек
fun decryptGpgSymmetric(
    inputFilePath: String,
    outputFilePath: String,
    passphrase: String
): Boolean {
    return try {
        decryptGpgSymmetricInternal(
            inputFile = File(inputFilePath),
            outputFile = File(outputFilePath),
            passphrase = passphrase.toCharArray()
        )
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}


    private fun decryptGpgSymmetricInternal(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray
    ) {
        Security.addProvider(BouncyCastleProvider())

        val decoder = PGPUtil.getDecoderStream(FileInputStream(inputFile))
        val pgpFactory = JcaPGPObjectFactory(decoder)

        var obj = pgpFactory.nextObject()

        val encryptedDataList = when (obj) {
            is PGPEncryptedDataList -> obj
            is PGPMarker -> pgpFactory.nextObject() as PGPEncryptedDataList
            else -> obj as PGPEncryptedDataList
        }

        val encryptedData = encryptedDataList.encryptedDataObjects.next() as PGPPBEEncryptedData

        val decryptorFactory = JcePBEDataDecryptorFactoryBuilder(
            JcaPGPDigestCalculatorProviderBuilder().build()
        )
            .setProvider("BC")
            .build(passphrase)

        val clear = encryptedData.getDataStream(decryptorFactory)
        val plainFactory = JcaPGPObjectFactory(clear)
        val message = plainFactory.nextObject()

        val literalData = when (message) {
            is PGPLiteralData -> message
            is PGPCompressedData -> {
                val compressedFactory = JcaPGPObjectFactory(message.dataStream)
                compressedFactory.nextObject() as PGPLiteralData
            }
            else -> throw IllegalArgumentException("Неизвестный формат PGP данных")
        }

        literalData.inputStream.use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }



    /**
     * Расшифровка симметрично (паролем) зашифрованного GPG/PGP файла из потока.
     * Используется вкладкой "GPG Decryptor": файл выбирается через SAF (Uri), путь не нужен.
     * Бросает исключение при неверном пароле / повреждённых данных.
     */
    fun decryptGpgSymmetric(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray
    ) {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())

        val decoder = PGPUtil.getDecoderStream(input)
        val pgpFactory = JcaPGPObjectFactory(decoder)

        var obj = pgpFactory.nextObject()
        while (obj != null && obj !is PGPEncryptedDataList) {
            obj = pgpFactory.nextObject()
        }
        val encryptedDataList = obj as? PGPEncryptedDataList
            ?: throw IllegalArgumentException("Это не зашифрованный GPG-файл")

        var pbeData: PGPPBEEncryptedData? = null
        val objects = encryptedDataList.encryptedDataObjects
        while (objects.hasNext()) {
            val o = objects.next()
            if (o is PGPPBEEncryptedData) { pbeData = o; break }
        }
        if (pbeData == null) {
            throw IllegalArgumentException(
                "Файл зашифрован не паролем (симметрично), а публичным ключом"
            )
        }

        val decryptorFactory = JcePBEDataDecryptorFactoryBuilder(
            JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build()
        ).setProvider("BC").build(passphrase)

        val clear = pbeData.getDataStream(decryptorFactory)
        var message = JcaPGPObjectFactory(clear).nextObject()

        // Разворачиваем сжатие (возможна вложенность)
        var factory: JcaPGPObjectFactory
        while (message is PGPCompressedData) {
            factory = JcaPGPObjectFactory(message.dataStream)
            message = factory.nextObject()
        }
        if (message is PGPOnePassSignatureList) {
            throw IllegalArgumentException("Подписанные сообщения (gpg --sign) не поддерживаются")
        }
        val literal = message as? PGPLiteralData
            ?: throw IllegalArgumentException("Неизвестный формат PGP данных")

        literal.inputStream.use { it.copyTo(output) }

        // Проверка целостности (MDC), если она есть в файле
        if (pbeData.isIntegrityProtected && !pbeData.verify()) {
            throw PGPException("Проверка целостности не пройдена: данные повреждены")
        }
    }

}