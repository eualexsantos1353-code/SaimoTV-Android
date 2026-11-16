package br.com.saimo.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * O Free My TV pergunta, antes de mostrar "Ver partida", se o canal do jogo existe na lista daqui.
 *
 * `content://br.com.saimo.tv.canais/canal?n=ESPN` devolve uma linha (`nome`, `tipo`): o nome exato
 * do canal que o link saimo://canal?n=ESPN abriria e como o nome bateu ([BuscaDeCanal.Tipo]). Sem
 * canal, nenhuma linha. É a mesma busca e a mesma lista do Ao vivo ([MainActivity]); os restritos
 * só entram se já estiverem destrancados neste processo.
 *
 * Só responde ao Free My TV (e ao adb, para testar). Nunca devolve endereço, chave nem a lista
 * inteira: só o nome de um canal.
 */
@UnstableApi
class ConsultaDeCanal : ContentProvider() {

    companion object {
        const val AUTORIDADE = "br.com.saimo.tv.canais"
        private val QUEM_PODE = setOf("io.freemytv", "com.android.shell")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("nome", "tipo"))
        if (callingPackage !in QUEM_PODE) return cursor
        if (uri.pathSegments.firstOrNull() != "canal") return cursor
        val pedido = uri.getQueryParameter("n")?.trim().orEmpty()
        if (pedido.isEmpty()) return cursor
        val contexto = context ?: return cursor
        esperarAbertura()
        val nomes = runCatching {
            // App fechado: a lista vem do disco, como na abertura do Ao vivo
            if (Remote.channels.isEmpty()) Remote.loadCached(contexto)
            Favorites.load(contexto)
            FontesDesativadas.peneirarCanais(
                Categorias.ordenar(MinhasListas.somar(Unlock.channels(), Unlock.unlocked))).map { it.name }
        }.getOrDefault(emptyList())
        val achado = BuscaDeCanal.encontrar(nomes, pedido) ?: return cursor
        cursor.addRow(arrayOf<Any?>(nomes[achado.indice], achado.tipo.name))
        return cursor
    }

    /**
     * O provider nasce antes do SaimoApp.onCreate, e a pergunta pode chegar enquanto ele ainda roda
     * (Minhas listas, preferências). Espera a linha principal ficar livre — no máximo 5 segundos.
     */
    private fun esperarAbertura() {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val livre = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { livre.countDown() }
        livre.await(5, TimeUnit.SECONDS)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
