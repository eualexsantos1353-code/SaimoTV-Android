package br.com.saimo.tv

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Os dados da Agenda: episódios, estreias de séries, temporadas novas e filmes que chegam ao Brasil.
 * Vem do Trakt (a agenda da conta, quando conectada, e as temporadas novas das séries populares) e do
 * TMDB (séries novas, filmes, datas no Brasil, títulos em português e capas). Sem telas aqui.
 *
 * Tudo roda fora da linha da tela. O resultado fica guardado por ~1 dia; títulos e capas, por 20 dias.
 * Nada é inventado: sem Trakt ou sem TMDB, as partes que dependem deles simplesmente não aparecem.
 */
object AgendaDados {
    const val EPISODIO = 0
    const val ESTREIA = 1
    const val TEMPORADA = 2
    const val FILME = 3

    private const val DIA = 86_400_000L
    private const val ADIANTE_DIAS = 75
    private const val VOTOS_POPULAR = 1500
    private const val LINGUA = "pt-BR"
    private const val REGIAO = "BR"
    private val IDIOMAS = setOf("en", "pt", "es", "ko", "ja", "fr", "de", "it", "sv", "da", "no", "nb", "tr", "nl", "pl")
    private val NAO_SERIE = setOf(10763, 10764, 10766, 10767)
    private val buscas = Semaphore(4)

    /** Uma coisa na agenda: episódio, temporada nova, série nova ou filme. */
    class Item {
        var kind = ""            // "tv" ou "movie"
        var titulo = ""
        var original = ""
        var emissora = ""
        var poster: String? = null
        var backdrop: String? = null
        var id = 0L              // id no TMDB
        var temporada = 0
        var numero = 0
        var tipo = EPISODIO
        var lancamento = 0       // filmes: 2 estreia limitada, 3 cinema, 4 digital
        var em = 0L              // o dia/hora (ms)
        var comHora = false
        var meu = false          // vem da conta do Trakt
        var pop = 0.0

        val serie get() = kind == "tv"

        fun toJson(): JSONObject = JSONObject().put("k", kind).put("t", titulo).put("o", original).put("n", emissora)
            .put("p", poster ?: "").put("b", backdrop ?: "").put("i", id).put("s", temporada).put("e", numero)
            .put("y", tipo).put("l", lancamento).put("a", em).put("h", comHora).put("m", meu).put("q", pop)

        companion object {
            fun deJson(o: JSONObject) = Item().apply {
                kind = o.optString("k"); titulo = o.optString("t"); original = o.optString("o"); emissora = o.optString("n")
                poster = vazioNulo(o.optString("p")); backdrop = vazioNulo(o.optString("b")); id = o.optLong("i")
                temporada = o.optInt("s"); numero = o.optInt("e"); tipo = o.optInt("y"); lancamento = o.optInt("l")
                em = o.optLong("a"); comHora = o.optBoolean("h"); meu = o.optBoolean("m"); pop = o.optDouble("q", 0.0)
            }
        }
    }

    /** Os quatro filtros da tela. */
    enum class Filtro { PROXIMOS, ESTREIAS, TEMPORADAS, FILMES }

    fun filtrar(lista: List<Item>, filtro: Filtro): List<Item> = lista.filter {
        when (filtro) {
            Filtro.PROXIMOS -> it.serie && it.meu
            Filtro.ESTREIAS -> it.tipo == ESTREIA
            Filtro.TEMPORADAS -> it.tipo == TEMPORADA
            Filtro.FILMES -> it.tipo == FILME
        }
    }

    /** Os itens de um dia, em ordem de hora (sem hora, depois). */
    fun doDia(lista: List<Item>, dia: Long): List<Item> {
        val inicio = inicioDoDia(dia)
        val fim = somarDias(inicio, 1)
        return lista.filter { it.em in inicio until fim }
            .sortedWith(compareBy<Item>({ !it.comHora }, { it.em }, { it.titulo.lowercase() }))
    }

    // MARK: - Carregar

    /** A agenda (do guardado quando ainda vale). [forcar] busca de novo. Lança erro só se não houver nada a mostrar. */
    suspend fun carregar(c: Context, forcar: Boolean = false): List<Item> = withContext(Dispatchers.IO) {
        val arquivo = File(c.cacheDir, "agenda.json")
        val guardado = lerGuardado(arquivo)
        if (!forcar && guardado != null && System.currentTimeMillis() - guardado.first < DIA) return@withContext guardado.second
        try {
            val lista = buscar(c)
            gravarGuardado(arquivo, lista)
            lista
        } catch (e: Exception) {
            guardado?.second ?: throw e
        }
    }

    /** Só o que já está guardado (para mostrar de imediato); vazio se não houver. */
    fun guardada(c: Context): List<Item> = lerGuardado(File(c.cacheDir, "agenda.json"))?.second.orEmpty()

    private fun lerGuardado(f: File): Pair<Long, List<Item>>? = try {
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("itens")
            o.getLong("em") to List(a.length()) { Item.deJson(a.getJSONObject(it)) }
        }
    } catch (e: Exception) { null }

    private fun gravarGuardado(f: File, lista: List<Item>) {
        try {
            val a = JSONArray()
            lista.forEach { a.put(it.toJson()) }
            f.writeText(JSONObject().put("em", System.currentTimeMillis()).put("itens", a).toString())
        } catch (e: Exception) { /* sem cache, segue */ }
    }

    private suspend fun buscar(c: Context): List<Item> {
        val agora = System.currentTimeMillis()
        val hoje = inicioDoDia(agora)
        val de = inicioDoMes(agora)
        val ate = hoje + ADIANTE_DIAS * DIA
        val porChave = LinkedHashMap<String, Item>()
        var falhou: Exception? = null

        if (Trakt.conectado(c)) {
            var w = de
            while (w < ate) {
                try { traktSeries(JSONArray(Trakt.pegar(c, "/calendars/my/shows/${ymd(w)}/31?extended=full")), true, porChave, de, ate) }
                catch (e: Exception) { /* esta janela falhou; segue */ }
                try { traktFilmes(JSONArray(Trakt.pegar(c, "/calendars/my/movies/${ymd(w)}/31")), porChave, de, ate) }
                catch (e: Exception) { /* idem */ }
                w += 31 * DIA
            }
        }
        if (Trakt.disponivel()) {
            var w = hoje
            while (w < ate) {
                try { traktSeries(JSONArray(Trakt.publico("/calendars/all/shows/premieres/${ymd(w)}/31?extended=full")), false, porChave, de, ate) }
                catch (e: Exception) { /* idem */ }
                w += 31 * DIA
            }
        }
        try { seriesNovas(de, ate, porChave) } catch (e: Exception) { falhou = e }
        try { filmes(de, ate, porChave) } catch (e: Exception) { falhou = e }
        meta(c, porChave.values)

        val saida = porChave.values.filter { it.titulo.isNotEmpty() && it.em > 0 }
            .sortedWith(compareBy<Item>({ it.em }, { it.titulo.lowercase() }))
        if (saida.isEmpty() && falhou != null) throw falhou
        return saida
    }

    // MARK: - Trakt

    private fun traktSeries(a: JSONArray, meu: Boolean, porChave: MutableMap<String, Item>, de: Long, ate: Long) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val show = o.optJSONObject("show") ?: continue
            val ep = o.optJSONObject("episode") ?: continue
            val tmdb = show.optJSONObject("ids")?.optLong("tmdb") ?: 0L
            val em = iso(o.optString("first_aired"))
            val temporada = ep.optInt("season")
            val numero = ep.optInt("number")
            if (tmdb <= 0 || em < de || em >= ate || temporada <= 0 || numero <= 0) continue
            val rotulo = ep.optString("episode_type")
            val tipo = when {
                rotulo == "series_premiere" || (temporada == 1 && numero == 1) -> ESTREIA
                rotulo == "season_premiere" || numero == 1 -> TEMPORADA
                else -> EPISODIO
            }
            val votos = show.optInt("votes")
            if (!meu && (tipo != TEMPORADA || votos < VOTOS_POPULAR)) continue
            val it = porChave.getOrPut("tv:$tmdb:$temporada:$numero") { Item() }
            it.kind = "tv"; it.id = tmdb; it.temporada = temporada; it.numero = numero; it.tipo = tipo
            it.em = em; it.comHora = true; it.meu = it.meu || meu
            if (it.titulo.isEmpty()) it.titulo = show.optString("title")
            if (it.emissora.isEmpty()) it.emissora = show.optString("network")
            it.pop = maxOf(it.pop, votos / 50.0)
        }
    }

    private fun traktFilmes(a: JSONArray, porChave: MutableMap<String, Item>, de: Long, ate: Long) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val filme = o.optJSONObject("movie") ?: continue
            val tmdb = filme.optJSONObject("ids")?.optLong("tmdb") ?: 0L
            val em = dia(o.optString("released"))
            if (tmdb <= 0 || em < de || em >= ate) continue
            val it = porChave.getOrPut("movie:$tmdb") { Item().also { n -> n.em = em } }
            it.kind = "movie"; it.id = tmdb; it.tipo = FILME; it.meu = true
            if (it.titulo.isEmpty()) it.titulo = filme.optString("title")
        }
    }

    // MARK: - TMDB

    private fun seriesNovas(de: Long, ate: Long, porChave: MutableMap<String, Item>) {
        for (pagina in 1..2) {
            val r = tmdb("/discover/tv?language=$LINGUA&sort_by=popularity.desc&first_air_date.gte=${ymd(de)}" +
                "&first_air_date.lte=${ymd(ate)}&without_genres=10763%2C10764%2C10766%2C10767&page=$pagina")
            val a = r.optJSONArray("results") ?: break
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("poster_path").isEmpty() || o.optString("original_language") !in IDIOMAS) continue
                if (temGenero(o, NAO_SERIE)) continue
                val em = dia(o.optString("first_air_date"))
                val id = o.optLong("id")
                if (id <= 0 || em < de || em >= ate) continue
                val it = porChave.getOrPut("tv:$id:1:1") { Item().also { n -> n.em = em; n.temporada = 1; n.numero = 1 } }
                it.kind = "tv"; it.id = id; it.tipo = ESTREIA
                it.titulo = o.optString("name", it.titulo)
                it.original = o.optString("original_name")
                it.poster = vazioNulo(o.optString("poster_path"))
                it.backdrop = vazioNulo(o.optString("backdrop_path"))
                it.pop = maxOf(it.pop, o.optDouble("popularity", 0.0))
            }
        }
    }

    private suspend fun filmes(de: Long, ate: Long, porChave: MutableMap<String, Item>) {
        val achados = ArrayList<JSONObject>()
        val vistos = HashSet<Long>()
        for (pagina in 1..2) {
            val r = tmdb("/discover/movie?language=$LINGUA&region=$REGIAO&sort_by=popularity.desc" +
                "&release_date.gte=${ymd(de)}&release_date.lte=${ymd(ate)}&with_release_type=2%7C3%7C4&page=$pagina")
            val a = r.optJSONArray("results") ?: break
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("poster_path").isEmpty() || !vistos.add(o.optLong("id"))) continue
                achados.add(o)
            }
        }
        // Os filmes das listas da pessoa também, para a data aqui.
        for (it in porChave.values.toList()) {
            if (it.kind == "movie" && vistos.add(it.id)) achados.add(JSONObject().put("id", it.id))
        }
        val datas = coroutineScope {
            achados.map { o ->
                async { buscas.withPermit { try { dataAqui(o.optLong("id"), de) } catch (e: Exception) { null } } }
            }.awaitAll()
        }
        for ((i, o) in achados.withIndex()) {
            val quando = datas[i]
            val id = o.optLong("id")
            val chave = "movie:$id"
            if (quando == null || quando.first < de || quando.first >= ate) {
                if (quando != null) porChave.remove(chave)   // é da pessoa, mas não sai aqui nestas semanas
                continue
            }
            val it = porChave.getOrPut(chave) { Item() }
            it.kind = "movie"; it.id = id; it.tipo = FILME; it.em = quando.first; it.lancamento = quando.second
            if (o.has("title")) {
                it.titulo = o.optString("title", it.titulo)
                it.original = o.optString("original_title")
                it.poster = vazioNulo(o.optString("poster_path"))
                it.backdrop = vazioNulo(o.optString("backdrop_path"))
                it.pop = maxOf(it.pop, o.optDouble("popularity", 0.0))
            }
        }
    }

    /** A primeira data do filme aqui, de [de] em diante (cinema, senão digital), e o tipo; nulo se não houver. */
    private fun dataAqui(id: Long, de: Long): Pair<Long, Int>? {
        val a = tmdb("/movie/$id/release_dates").optJSONArray("results") ?: return null
        var melhor = 0L
        var tipo = 0
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (!REGIAO.equals(c.optString("iso_3166_1"), ignoreCase = true)) continue
            val d = c.optJSONArray("release_dates") ?: continue
            for (j in 0 until d.length()) {
                val x = d.optJSONObject(j) ?: continue
                val t = x.optInt("type")
                if (t < 2 || t > 4) continue
                val em = dia(x.optString("release_date"))
                if (em < de) continue
                if (melhor == 0L || em < melhor) { melhor = em; tipo = t }
            }
        }
        return if (melhor == 0L) null else melhor to tipo
    }

    /** Títulos em português e imagens (TMDB) do que veio só com o título do Trakt; guardados por 20 dias. */
    private suspend fun meta(c: Context, itens: Collection<Item>) {
        val arquivo = File(c.cacheDir, "agenda-meta.json")
        val sabidos = try { if (arquivo.exists()) JSONObject(arquivo.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        val agora = System.currentTimeMillis()
        val pedir = LinkedHashMap<String, Pair<String, Long>>()
        for (it in itens) {
            if (it.poster != null) continue
            val chave = "${it.kind}:${it.id}"
            val m = sabidos.optJSONObject(chave)
            val fresco = m != null && agora - m.optLong("at") < 20 * DIA && LINGUA == m.optString("lang")
            if (!fresco) pedir.putIfAbsent(chave, it.kind to it.id)
        }
        val respostas = coroutineScope {
            pedir.map { (chave, par) ->
                async {
                    buscas.withPermit {
                        try {
                            val (kind, id) = par
                            val o = tmdb("/$kind/$id?language=$LINGUA")
                            val rede = o.optJSONArray("networks")?.optJSONObject(0)
                            val filme = kind == "movie"
                            chave to JSONObject()
                                .put("t", o.optString(if (filme) "title" else "name"))
                                .put("o", o.optString(if (filme) "original_title" else "original_name"))
                                .put("p", o.optString("poster_path")).put("b", o.optString("backdrop_path"))
                                .put("n", rede?.optString("name").orEmpty())
                                .put("lang", LINGUA).put("at", System.currentTimeMillis())
                        } catch (e: Exception) { null }
                    }
                }
            }.awaitAll()
        }
        var mudou = false
        for (r in respostas) if (r != null) { sabidos.put(r.first, r.second); mudou = true }
        if (mudou) try { arquivo.writeText(sabidos.toString()) } catch (e: Exception) { /* sem cache, segue */ }
        for (it in itens) {
            if (it.poster != null) continue
            val m = sabidos.optJSONObject("${it.kind}:${it.id}") ?: continue
            if (m.optString("t").isNotEmpty()) it.titulo = m.optString("t")
            it.original = m.optString("o")
            it.poster = vazioNulo(m.optString("p"))
            it.backdrop = vazioNulo(m.optString("b"))
            if (m.optString("n").isNotEmpty()) it.emissora = m.optString("n")
        }
    }

    private fun tmdb(caminho: String): JSONObject {
        val chave = BuildConfig.TMDB_KEY.trim()
        if (chave.isEmpty()) throw IOException("sem chave do TMDB")
        val h = URL("https://api.themoviedb.org/3" + caminho + (if ('?' in caminho) "&" else "?") + "api_key=" + chave)
            .openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 8_000
            h.readTimeout = 10_000
            h.instanceFollowRedirects = false
            h.setRequestProperty("User-Agent", "ASTRAL-TV")
            if (h.responseCode != 200) throw IOException("HTTP ${h.responseCode}")
            return JSONObject(h.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        } finally {
            h.disconnect()
        }
    }

    private fun temGenero(o: JSONObject, generos: Set<Int>): Boolean {
        val g = o.optJSONArray("genre_ids") ?: return false
        return (0 until g.length()).any { g.optInt(it) in generos }
    }

    private fun vazioNulo(s: String?) = if (s.isNullOrEmpty() || s == "null") null else s

    // MARK: - Datas

    fun ymd(em: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(em))

    /** "2026-11-20" (ou o começo de um horário ISO) → a meia-noite desse dia aqui; 0 se não entender. */
    fun dia(s: String?): Long {
        if (s == null || s.length < 10) return 0
        return try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s.substring(0, 10))!!.time } catch (e: Exception) { 0 }
    }

    /** "2026-11-10T01:00:00.000Z" → milissegundos; 0 se não entender. */
    fun iso(s: String?): Long {
        if (s == null || s.length < 19) return 0
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s.substring(0, 19))!!.time
        } catch (e: Exception) { 0 }
    }

    fun inicioDoDia(em: Long): Long = Calendar.getInstance().apply {
        timeInMillis = em
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun inicioDoMes(em: Long): Long = Calendar.getInstance().apply {
        timeInMillis = inicioDoDia(em)
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun somarDias(dia: Long, n: Int): Long = inicioDoDia(Calendar.getInstance().apply {
        timeInMillis = dia
        add(Calendar.DAY_OF_YEAR, n)
    }.timeInMillis)

    /** Quantos dias de [a] até [b] (negativo se b é antes). */
    fun diasEntre(a: Long, b: Long): Int = Math.round((inicioDoDia(b) - inicioDoDia(a)) / DIA.toDouble()).toInt()
}
