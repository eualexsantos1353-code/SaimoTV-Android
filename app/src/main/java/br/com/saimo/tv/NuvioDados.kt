package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Os títulos das fileiras da página Nuvio: o que a pessoa está assistindo e a lista dela, do Trakt
 * (que o Nuvio mantém sincronizado), com as capas do TMDB. Só lê; nada é inventado: sem Trakt, sem rede
 * ou sem resposta, a lista vem vazia. Sem chave do TMDB os títulos aparecem sem capa.
 */
object NuvioDados {

    data class Titulo(
        val serie: Boolean,
        val nome: String,
        /** Temporada e episódio (séries em andamento) ou o ano. Vazio quando o Trakt não informa. */
        val detalhe: String,
        /** De 0 a 1, só em "continuar assistindo". */
        val progresso: Float?,
        val imdb: String?,
        val tmdb: Long?,
        val capa: String?,
    )

    private data class Bruto(
        val serie: Boolean,
        val nome: String,
        val detalhe: String,
        val progresso: Float?,
        val imdb: String?,
        val tmdb: Long?,
        val quando: String,
    )

    private const val LIMITE = 15
    private val cache = ConcurrentHashMap<String, Pair<String?, String?>>()

    suspend fun continuar(c: Context): List<Titulo> = withContext(Dispatchers.IO) {
        comCapas(try { lerContinuar(c) } catch (e: Exception) { emptyList() })
    }

    suspend fun lista(c: Context): List<Titulo> = withContext(Dispatchers.IO) {
        comCapas(try { lerLista(c) } catch (e: Exception) { emptyList() })
    }

    // MARK: - Trakt

    private fun bruto(no: JSONObject, serie: Boolean, detalhe: String, progresso: Float?, quando: String): Bruto {
        val ids = no.optJSONObject("ids")
        val imdb = ids?.optString("imdb")?.takeIf { it.isNotBlank() && it != "null" }
        val tmdb = ids?.optLong("tmdb", 0L)?.takeIf { it > 0L }
        return Bruto(serie, no.optString("title"), detalhe, progresso, imdb, tmdb, quando)
    }

    /** O que foi começado e não terminado, do mais recente para o mais antigo, um por título. */
    private fun lerContinuar(c: Context): List<Bruto> {
        val itens = JSONArray(Trakt.pegar(c, "/sync/playback"))
        val saida = ArrayList<Bruto>()
        for (i in 0 until itens.length()) {
            val o = itens.optJSONObject(i) ?: continue
            val p = o.optDouble("progress", 0.0)
            if (p <= 0.0 || p >= 100.0) continue
            val quando = o.optString("paused_at")
            val fracao = (p / 100.0).toFloat()
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.let { saida += bruto(it, false, "", fracao, quando) }
                "episode" -> {
                    val serie = o.optJSONObject("show") ?: continue
                    val ep = o.optJSONObject("episode")
                    val detalhe = if (ep != null && ep.has("season") && ep.has("number"))
                        "T${ep.optInt("season")} · E${ep.optInt("number")}" else ""
                    saida += bruto(serie, true, detalhe, fracao, quando)
                }
            }
        }
        return saida.sortedByDescending { it.quando }
            .distinctBy { "${it.serie}|${it.imdb ?: it.tmdb ?: it.nome}" }
            .take(LIMITE)
    }

    /** A lista (watchlist): filmes e séries, do mais novo para o mais antigo. */
    private fun lerLista(c: Context): List<Bruto> {
        val saida = ArrayList<Bruto>()
        for ((caminho, chave, serie) in listOf(
            Triple("/sync/watchlist/movies", "movie", false),
            Triple("/sync/watchlist/shows", "show", true),
        )) {
            val itens = try { JSONArray(Trakt.pegar(c, caminho)) } catch (e: Exception) { continue }
            for (i in 0 until itens.length()) {
                val o = itens.optJSONObject(i) ?: continue
                val no = o.optJSONObject(chave) ?: continue
                val ano = no.optInt("year").takeIf { it > 0 }?.toString().orEmpty()
                saida += bruto(no, serie, ano, null, o.optString("listed_at"))
            }
        }
        return saida.sortedByDescending { it.quando }.take(LIMITE)
    }

    // MARK: - TMDB (capas)

    private suspend fun comCapas(brutos: List<Bruto>): List<Titulo> = coroutineScope {
        val vez = Semaphore(4)   // a TV é fraca: poucas conexões ao mesmo tempo
        brutos.map { b ->
            async {
                val (capa, nomeEmPortugues) = vez.withPermit { capaTmdb(b.serie, b.tmdb) }
                Titulo(b.serie, nomeEmPortugues ?: b.nome, b.detalhe, b.progresso, b.imdb, b.tmdb, capa)
            }
        }.awaitAll()
    }

    /** O poster e o nome em português de um título, ou nulos sem chave, sem rede ou sem resposta. */
    private fun capaTmdb(serie: Boolean, tmdb: Long?): Pair<String?, String?> {
        val chave = BuildConfig.TMDB_KEY
        if (chave.isEmpty() || tmdb == null) return null to null
        val caminho = "${if (serie) "tv" else "movie"}/$tmdb"
        cache[caminho]?.let { return it }
        val resposta: Pair<String?, String?> = try {
            val h = URL("https://api.themoviedb.org/3/$caminho?api_key=$chave&language=pt-BR")
                .openConnection() as HttpURLConnection
            try {
                h.connectTimeout = 8_000
                h.readTimeout = 10_000
                h.instanceFollowRedirects = false
                if (h.responseCode != 200) return null to null
                val j = JSONObject(h.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                val poster = j.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { "https://image.tmdb.org/t/p/w342$it" }
                val nome = j.optString(if (serie) "name" else "title").takeIf { it.isNotBlank() }
                poster to nome
            } finally {
                h.disconnect()
            }
        } catch (e: Exception) {
            null to null
        }
        if (resposta.first != null || resposta.second != null) cache[caminho] = resposta
        return resposta
    }
}
