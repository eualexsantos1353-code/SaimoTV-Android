package br.com.saimo.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda textos pequenos (os tokens do Trakt) cifrados com uma chave do Android Keystore.
 * Nada vai em texto puro para o disco. Se o Keystore falhar, ler devolve null e gravar devolve false:
 * quem chama trata como "sem login", nunca com um valor de reserva.
 */
internal object Cofre {
    private const val ALIAS = "astral_cofre"
    private const val ARQUIVO = "astral_cofre"

    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        return gerador.generateKey()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    fun guardar(c: Context, nome: String, valor: String): Boolean = try {
        val cifra = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chave()) }
        val saida = cifra.iv + cifra.doFinal(valor.toByteArray(Charsets.UTF_8))
        prefs(c).edit().putString(nome, Base64.encodeToString(saida, Base64.NO_WRAP)).apply()
        true
    } catch (e: Exception) {
        false
    }

    fun ler(c: Context, nome: String): String? = try {
        val bruto = Base64.decode(prefs(c).getString(nome, null) ?: return null, Base64.NO_WRAP)
        val cifra = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, bruto, 0, 12)) }
        String(cifra.doFinal(bruto, 12, bruto.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun limpar(c: Context) {
        prefs(c).edit().clear().apply()
    }
}
