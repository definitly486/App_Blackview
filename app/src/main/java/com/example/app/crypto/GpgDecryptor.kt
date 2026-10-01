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
         var message = JcaPGPObjectFactory(clear).nextObject()

         while (message is PGPCompressedData) {
             message = JcaPGPObjectFactory(message.dataStream).nextObject()
         }

         if (message is PGPOnePassSignatureList) {
             throw IllegalArgumentException(
                 "Подписанные сообщения не поддерживаются"
             )
         }

         val literal = message as? PGPLiteralData
             ?: throw IllegalArgumentException(
                 "Неизвестный формат PGP данных"
             )

         literal.inputStream.use { literalInput ->
             literalInput.copyTo(output)
         }

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
  *
  * The caller owns the streams and is responsible for closing them.
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

  private fun ensureProvider() {
  if (Security.getProvider(BC_PROVIDER) == null) {
  Security.addProvider(BouncyCastleProvider())
  }
  }

  private companion object {
  const val BC_PROVIDER = "BC"
  }
  }
