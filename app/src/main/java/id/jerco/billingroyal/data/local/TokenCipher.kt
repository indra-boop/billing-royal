package id.jerco.billingroyal.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Enkripsi simetris untuk token. Implementasi produksi memakai Android Keystore (AES/GCM). */
interface TokenCipher {
    /** Hasil: IV (12 byte) + ciphertext + tag. */
    fun encrypt(plain: ByteArray): ByteArray

    /** Melempar exception bila data rusak atau kunci tidak lagi valid. */
    fun decrypt(data: ByteArray): ByteArray

    /** Menghapus kunci dari Keystore. */
    fun deleteKey()
}

class AndroidKeystoreCipher(private val alias: String = KEY_ALIAS) : TokenCipher {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // IV acak dibuat oleh Keystore
        val iv = cipher.iv
        require(iv.size == IV_SIZE) { "Ukuran IV tidak terduga" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE) { "Data terenkripsi tidak valid" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE),
        )
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    override fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "billing_royal_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
