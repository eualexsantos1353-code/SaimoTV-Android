package br.com.saimo.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Os jogos da fileira "Jogos" do Início: os placares de hoje de alguns campeonatos, direto da ESPN.
 *
 * Só placar, horário e escudo. A ESPN não diz em que canal da lista o jogo passa, então esta fileira
 * não liga jogo a canal: nada aqui finge que há transmissão.
 */
object JogosInicio {

    data class Jogo(
        val casa: String,
        val fora: String,
        val casaLogo: String,
        val foraLogo: String,
        val liga: String,
        val ligaLogo: String,
        val inicio: Long,
        /** "pre" (vai começar), "in" (ao vivo) ou "post" (acabou). */
        val estado: String,
        val placar: String,
    ) {
        val titulo get() = "$casa x $fora"
    }

    private val LIGAS = listOf(
        "bra.1" to "Brasileirão Série A",
        "bra.2" to "Brasileirão Série B",
        "bra.copa_do_brazil" to "Copa do Brasil",
        "conmebol.libertadores" to "Libertadores",
        "conmebol.sudamericana" to "Sul-Americana",
        "fifa.worldq.conmebol" to "Eliminatórias",
        "uefa.champions" to "Champions League",
        "uefa.europa" to "Europa League",
        "eng.1" to "Premier League",
        "esp.1" to "La Liga",
        "ita.1" to "Série A (Itália)",
        "ger.1" to "Bundesliga",
    )

    private const val INTERVALO_MS = 90_000L
    private const val MAXIMO = 12

    @Volatile
    var jogos: List<Jogo> = emptyList()
        private set

    @Volatile
    private var ultima = 0L

    private val cliente by lazy {
        Playback.client.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Busca de novo (no máximo a cada 90 s). Devolve se a lista mudou. */
    suspend fun atualizar(): Boolean = withContext(Dispatchers.IO) {
        val agora = System.currentTimeMillis()
        if (agora - ultima < INTERVALO_MS) return@withContext false
        ultima = agora
        val respostas = coroutineScope {
            LIGAS.map { (slug, nome) -> async { runCatching { baixar(slug, nome) }.getOrNull() } }.awaitAll()
        }
        // Rede fora: fica o que já estava, em vez de sumir com a fileira.
        if (respostas.all { it == null }) return@withContext false
        val novos = ordenar(respostas.filterNotNull().flatten(), agora)
        val mudou = novos != jogos
        jogos = novos
        mudou
    }

    /** Ao vivo primeiro, depois os que vão começar, depois os que acabaram há pouco. */
    private fun ordenar(todos: List<Jogo>, agora: Long): List<Jogo> {
        val vivos = todos.filter { it.estado == "in" }.sortedBy { it.inicio }
        val proximos = todos.filter { it.estado == "pre" }.sortedBy { it.inicio }
        val acabados = todos.filter { it.estado == "post" && agora - it.inicio < 8 * 3_600_000L }
            .sortedByDescending { it.inicio }
        return (vivos + proximos + acabados).distinctBy { "${it.titulo}|${it.inicio}" }.take(MAXIMO)
    }

    private fun baixar(slug: String, nomeDaLiga: String): List<Jogo>? {
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard"
        val corpo = cliente.newCall(
            Request.Builder().url(url).header("User-Agent", Playback.DEFAULT_USER_AGENT).build(),
        ).execute().use { r -> if (r.isSuccessful) r.body.string() else null } ?: return null
        val json = JSONObject(corpo)
        val ligaLogo = json.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")
            ?.optJSONObject(0)?.optString("href").orEmpty()
        val eventos = json.optJSONArray("events") ?: return emptyList()
        val data = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return (0 until eventos.length()).mapNotNull { i ->
            runCatching {
                val ev = eventos.getJSONObject(i)
                val comps = ev.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")
                    ?: return@runCatching null
                var casa: JSONObject? = null
                var fora: JSONObject? = null
                for (c in 0 until comps.length()) {
                    val comp = comps.getJSONObject(c)
                    if (comp.optString("homeAway") == "home") casa = comp else fora = comp
                }
                val ca = casa ?: return@runCatching null
                val fo = fora ?: return@runCatching null
                val estado = ev.optJSONObject("status")?.optJSONObject("type")?.optString("state").orEmpty()
                val placar = if (estado == "pre") "" else "${ca.optString("score")} x ${fo.optString("score")}"
                Jogo(
                    casa = ca.getJSONObject("team").optString("name"),
                    fora = fo.getJSONObject("team").optString("name"),
                    casaLogo = ca.getJSONObject("team").optString("logo"),
                    foraLogo = fo.getJSONObject("team").optString("logo"),
                    liga = nomeDaLiga,
                    ligaLogo = ligaLogo,
                    inicio = data.parse(ev.optString("date"))?.time ?: return@runCatching null,
                    estado = estado,
                    placar = placar,
                )
            }.getOrNull()
        }
    }
}
