@file:Suppress("SpellCheckingInspection")

package com.example.app.fragments

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.jcajce.*
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder
import java.io.File
import java.io.FileInputStream
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


}