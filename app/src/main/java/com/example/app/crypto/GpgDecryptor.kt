package com.example.app.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBEDataDecryptorFactoryBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.Security

/**
 * Symmetric OpenPGP/GPG decryption using Bouncy Castle.
 *
 * Supports:
 * - gpg --symmetric
 * - compressed PGP data
 * - encrypted + signed messages
 * - ASCII armored and binary GPG input
 */
class GpgDecryptor {

    /**
     * Decrypts a symmetric OpenPGP/GPG stream.
 *
     * The caller owns the input/output streams and is responsible for
     * closing them.
     */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray
    ) {
        ensureProvider()

        PGPUtil.getDecoderStream(input).use { decoder ->
            val encryptedData = findEncryptedData(decoder)

            val decryptor = JcePBEDataDecryptorFactoryBuilder(
                JcaPGPDigestCalculatorProviderBuilder()
                    .setProvider(BC_PROVIDER)
                    .build()
            )
                .setProvider(BC_PROVIDER)
                .build(passphrase)

            encryptedData.getDataStream(decryptor).use { clear ->
                val literal = findLiteralData(clear)

                literal.inputStream.use { literalInput ->
                    literalInput.copyTo(output)
                }

                output.flush()

                if (encryptedData.isIntegrityProtected &&
                    !encryptedData.verify()
                ) {
                    throw PGPException(
                        "Проверка целостности не пройдена: данные повреждены"
                    )
                }
            }
        }
    }

    /**
     * Decrypts an input file into an output file.
     */
    fun decrypt(
        inputFile: File,
        outputFile: File,
        passphrase: CharArray
    ) {
        inputFile.inputStream().use { input ->
            outputFile.outputStream().use { output ->
                decrypt(input, output, passphrase)
            }
        }
    }

    /**
     * Compatibility API for callers using streams and a CharArray password.
     */
    fun decryptGpgSymmetric(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray
    ) {
        decrypt(input, output, passphrase)
    }

    /**
     * Compatibility API retained for existing file-path callers.
     */
    fun decryptGpgSymmetric(
        inputFilePath: String,
        outputFilePath: String,
        passphrase: String
    ): Boolean = runCatching {
        val chars = passphrase.toCharArray()

        try {
            decrypt(
                File(inputFilePath),
                File(outputFilePath),
                chars
            )
        } finally {
            chars.fill('\u0000')
        }
    }.isSuccess

    /**
     * Finds the symmetric encrypted data packet.
     */
    private fun findEncryptedData(input: InputStream): PGPPBEEncryptedData {
        val factory = JcaPGPObjectFactory(input)
        var objectValue = factory.nextObject()

        while (
            objectValue != null &&
            objectValue !is PGPEncryptedDataList
        ) {
            objectValue = factory.nextObject()
        }

        val encryptedList = objectValue as? PGPEncryptedDataList
            ?: throw IllegalArgumentException(
                "Это не зашифрованный GPG-файл"
            )

        val objects = encryptedList.encryptedDataObjects

        while (objects.hasNext()) {
            val value = objects.next()

            if (value is PGPPBEEncryptedData) {
                return value
            }
        }

        throw IllegalArgumentException(
            "Файл зашифрован не паролем (симметрично), а публичным ключом"
        )
    }

    /**
     * Walks through compressed/signed PGP packets until the actual
     * literal file data is found.
     *
     * One-pass signatures are intentionally ignored here.
     * This class is responsible for decryption, not signature verification.
     */
    private fun findLiteralData(input: InputStream): PGPLiteralData {
        var currentFactory = JcaPGPObjectFactory(input)
        var objectValue = currentFactory.nextObject()

        while (objectValue != null) {
            when (objectValue) {
                is PGPCompressedData -> {
                    currentFactory =
                        JcaPGPObjectFactory(objectValue.dataStream)
                    objectValue = currentFactory.nextObject()
                }

                is PGPOnePassSignatureList -> {
                    objectValue = currentFactory.nextObject()
                }

                is PGPLiteralData -> {
                    return objectValue
                }

                else -> {
                    objectValue = currentFactory.nextObject()
                }
            }
        }

        throw IllegalArgumentException(
            "В расшифрованном GPG-файле не найдено содержимое файла"
        )
    }

    private fun ensureProvider() {
        if (Security.getProvider(BC_PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private companion object {
        const val BC_PROVIDER = "BC"
    }
}