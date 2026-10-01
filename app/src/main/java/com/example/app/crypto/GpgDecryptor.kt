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

/**
 * Symmetric OpenPGP/GPG decryption using Bouncy Castle.
 *
 * Supports:
 * - gpg --symmetric
 * - binary GPG files
 * - ASCII-armored PGP files
 * - compressed data
 * - encrypted + signed messages
 *
 * Signature verification is intentionally not performed here.
 * This class is responsible only for decrypting the data.
 */
class GpgDecryptor {

    /**
     * Decrypts a symmetric OpenPGP/GPG stream.
     *
     * The caller owns the input/output streams and is responsible
     * for closing them.
     */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray
    ) {
        PGPUtil.getDecoderStream(input).use { decoder ->

            val encryptedData = findEncryptedData(decoder)

            /*
             * IMPORTANT:
             *
             * Do not use setProvider("BC") here.
             *
             * Android can already have a provider registered under
             * the name "BC". That provider may not be the same
             * Bouncy Castle implementation bundled with the app and
             * can cause errors such as:
             *
             * "No such algorithm SHA-256 for provider BC"
             *
             * Passing the actual BouncyCastleProvider instance
             * avoids the provider-name collision.
             */
            val provider = BouncyCastleProvider()

            val decryptor = JcePBEDataDecryptorFactoryBuilder(
                JcaPGPDigestCalculatorProviderBuilder()
                    .setProvider(provider)
                    .build()
            )
                .setProvider(provider)
                .build(passphrase)

            encryptedData.getDataStream(decryptor).use { clear ->

                val literal = findLiteralData(clear)

                literal.inputStream.use { literalInput ->
                    literalInput.copyTo(output)
                }

                output.flush()

                /*
                 * Verify the integrity packet after all plaintext
                 * has been read.
                 */
                if (
                    encryptedData.isIntegrityProtected &&
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
                decrypt(
                    input,
                    output,
                    passphrase
                )
            }
        }
    }

    /**
     * Compatibility API used by GpgDecryptFragment.
     */
    fun decryptGpgSymmetric(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray
    ) {
        decrypt(
            input,
            output,
            passphrase
        )
    }

    /**
     * Compatibility API for existing callers using file paths
     * and a String password.
     *
     * Returns true when decryption succeeds, false otherwise.
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
     * Finds the symmetric password-based encrypted packet.
     */
    private fun findEncryptedData(
        input: InputStream
    ): PGPPBEEncryptedData {

        val factory = JcaPGPObjectFactory(input)

        var objectValue = factory.nextObject()

        /*
         * Skip packets before PGPEncryptedDataList.
         */
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

        /*
         * GPG may contain several encrypted data objects.
         *
         * We specifically need password-based encryption:
         * PGPPBEEncryptedData.
         */
        val objects = encryptedList.encryptedDataObjects

        while (objects.hasNext()) {
            val value = objects.next()

            if (value is PGPPBEEncryptedData) {
                return value
            }
        }

        throw IllegalArgumentException(
            "Файл зашифрован не паролем (симметрично), " +
                "а публичным ключом"
        )
    }

    /**
     * Walks through the decrypted PGP packet structure until
     * the actual file data (PGPLiteralData) is found.
     *
     * Supported structure examples:
     *
     * PGPCompressedData
     *     -> PGPLiteralData
     *
     * PGPCompressedData
     *     -> PGPOnePassSignatureList
     *     -> PGPLiteralData
     *
     * One-pass signatures are intentionally ignored.
     * We only need to extract the decrypted file contents.
     */
    private fun findLiteralData(
        input: InputStream
    ): PGPLiteralData {

        var factory = JcaPGPObjectFactory(input)
        var objectValue = factory.nextObject()

        while (objectValue != null) {

            when (objectValue) {

                is PGPCompressedData -> {
                    /*
                     * Enter the compressed packet and continue
                     * parsing its contents.
                     */
                    factory = JcaPGPObjectFactory(
                        objectValue.dataStream
                    )

                    objectValue = factory.nextObject()
                }

                is PGPOnePassSignatureList -> {
                    /*
                     * The message is signed.
                     *
                     * Signature verification is outside the scope
                     * of this decryptor, so skip the signature packet
                     * and continue to the actual file data.
                     */
                    objectValue = factory.nextObject()
                }

                is PGPLiteralData -> {
                    return objectValue
                }

                else -> {
                    /*
                     * Skip unrelated packets and continue searching
                     * for the literal data packet.
                     */
                    objectValue = factory.nextObject()
                }
            }
        }

        throw IllegalArgumentException(
            "В расшифрованном GPG-файле " +
                "не найдено содержимое файла"
        )
    }
}