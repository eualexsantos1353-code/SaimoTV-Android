package br.com.saimo.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * O Free My TV pergunta coisas da lista e do guia daqui. Só leitura, só texto: nunca endereço, chave nem a lista
 * inteira. A lista é a mesma do Ao vivo ([MainActivity]); os restritos só entram se já estiverem destrancados neste
 * processo.
 *
 * - `content://br.com.saimo.tv.canais/canal?n=ESPN`: uma linha (`nome`, `tipo`), o nome exato do canal que o link
 *   saimo://canal?n=ESPN abriria e como o nome bateu ([BuscaDeCanal.Tipo]); sem canal, nenhuma linha.
 * - `…/agora?n=HBO`: o que passa agora nesse canal e o que vem depois (uma linha de programa; `titulo` vazio quando o
 *   canal existe mas não tem guia).
 * - `…/tocando`: o mesmo, do último canal aberto no Ao vivo.
 * - `…/procurar?tipo=filme` (ou `?titulo=duna`): até 10 programas no ar agora desse tipo ([ProcuraNoGuia.TIPOS]), ou
 *   com esse nome no ar agora ou nas próximas 12 horas.
 *
 * Linha de programa: `canal`, `tipo` (como o canal bateu, em `agora`), `titulo`, `categoria`, `inicio`, `fim`
 * (milissegundos), `episodio`, `ano`, `sinopse`, `depois`, `depois_inicio`.
 *
 * Só responde ao Free My TV (e ao adb, para testar).
 */
@UnstableApi
class ConsultaDeCanal : ContentProvider() {

    companion object {
        const val AUTORIDADE = "br.com.saimo.tv.canais"
        private val QUEM_PODE = setOf("io.freemytv", "com.android.shell")
        private val PROGRAMA = arrayOf("canal", "tipo", "titulo", "categoria", "inicio", "fim", "episodio", "ano",
            "sinopse", "depois", "depois_inicio")
        private const val MAXIMO = 10
        private const val SINOPSE_MAX = 300
        private const val HORA = 3_600_000L
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val rota = uri.pathSegments.firstOrNull().orEmpty()
        val vazio = MatrixCursor(if (rota == "canal") arrayOf("nome", "tipo") else PROGRAMA)
        if (callingPackage !in QUEM_PODE) return vazio
        val contexto = context ?: return vazio
        esperarAbertura()
        return runCatching {
            when (rota) {
                "canal" -> canal(contexto, uri.getQueryParameter("n")?.trim().orEmpty())
                "agora" -> agora(contexto, uri.getQueryParameter("n")?.trim().orEmpty())
                "tocando" -> tocando(contexto)
                "procurar" -> procurar(contexto, uri.getQueryParameter("tipo")?.trim().orEmpty(),
                    uri.getQueryParameter("titulo")?.trim().orEmpty())
                else -> vazio
            }
        }.getOrDefault(vazio)
    }

    /** Os nomes que o Ao vivo mostra, na ordem dele: a lista do disco se o app estava fechado. */
    private fun nomes(contexto: android.content.Context): List<String> {
        if (Remote.channels.isEmpty()) Remote.loadCached(contexto)
        Favorites.load(contexto)
        return FontesDesativadas.peneirarCanais(
            Categorias.ordenar(MinhasListas.somar(Unlock.channels(), Unlock.unlocked))).map { it.name }
    }

    private fun canal(contexto: android.content.Context, pedido: String): Cursor {
        val cursor = MatrixCursor(arrayOf("nome", "tipo"))
        if (pedido.isEmpty()) return cursor
        val nomes = nomes(contexto)
        val achado = BuscaDeCanal.encontrar(nomes, pedido) ?: return cursor
        cursor.addRow(arrayOf<Any?>(nomes[achado.indice], achado.tipo.name))
        return cursor
    }

    private fun agora(contexto: android.content.Context, pedido: String): Cursor {
        val cursor = MatrixCursor(PROGRAMA)
        if (pedido.isEmpty()) return cursor
        val nomes = nomes(contexto)
        val achado = BuscaDeCanal.encontrar(nomes, pedido) ?: return cursor
        guia(contexto)
        linha(cursor, nomes[achado.indice], achado.tipo.name, System.currentTimeMillis())
        return cursor
    }

    private fun tocando(contexto: android.content.Context): Cursor {
        val cursor = MatrixCursor(PROGRAMA)
        if (!Preferencias.estaPronta()) Preferencias.iniciar(contexto)
        val ultimo = Preferencias.ultimoCanal ?: return cursor
        if (ultimo !in nomes(contexto)) return cursor   // já não está na lista (ou é restrito e está trancado)
        guia(contexto)
        linha(cursor, ultimo, "", System.currentTimeMillis())
        return cursor
    }

    private fun procurar(contexto: android.content.Context, tipo: String, titulo: String): Cursor {
        val cursor = MatrixCursor(PROGRAMA)
        if (tipo.isEmpty() && titulo.isEmpty()) return cursor
        if (tipo.isNotEmpty() && tipo !in ProcuraNoGuia.TIPOS) return cursor
        val nomes = nomes(contexto)
        guia(contexto)
        val agora = System.currentTimeMillis()
        val achados = ArrayList<Pair<String, Programme>>()
        val vistos = HashSet<String>()   // o mesmo canal em várias qualidades: uma vez só
        for (nome in nomes) {
            val chave = BuscaDeCanal.base(nome)
            if (chave in vistos) continue
            if (titulo.isNotEmpty()) {
                val p = Epg.schedule(nome).firstOrNull {
                    it.stop > agora && it.start < agora + 12 * HORA && ProcuraNoGuia.tituloTem(it.title, titulo)
                } ?: continue
                vistos += chave
                achados += nome to p
            } else {
                val p = Epg.nowNext(nome, agora)?.first ?: continue
                if (!ProcuraNoGuia.ehDoTipo(p.category, tipo)) continue
                vistos += chave
                achados += nome to p
            }
        }
        // Com nome: o que começa antes primeiro. Por tipo: o que começou há menos tempo primeiro (ainda dá para ver).
        val ordem = if (titulo.isNotEmpty()) achados.sortedBy { it.second.start } else achados.sortedByDescending { it.second.start }
        for ((nome, p) in ordem.take(MAXIMO)) {
            val depois = Epg.schedule(nome).firstOrNull { it.start >= p.stop }
            cursor.addRow(programa(nome, "", p, depois))
        }
        return cursor
    }

    /** O guia do disco, se ainda não está na memória (sem baixar nada). */
    private fun guia(contexto: android.content.Context) {
        runBlocking { Epg.carregarCache(contexto) }
    }

    private fun linha(cursor: MatrixCursor, canal: String, tipo: String, agora: Long) {
        val par = Epg.nowNext(canal, agora)
        if (par == null) {
            cursor.addRow(arrayOf<Any?>(canal, tipo, "", "", 0L, 0L, "", "", "", "", 0L))
            return
        }
        cursor.addRow(programa(canal, tipo, par.first, par.second))
    }

    private fun programa(canal: String, tipo: String, p: Programme, depois: Programme?): Array<Any?> = arrayOf(
        canal, tipo, p.title, p.category, p.start, p.stop, p.episodeLabel.orEmpty(), p.year.orEmpty(),
        p.description.orEmpty().take(SINOPSE_MAX), depois?.title.orEmpty(), depois?.start ?: 0L,
    )

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
