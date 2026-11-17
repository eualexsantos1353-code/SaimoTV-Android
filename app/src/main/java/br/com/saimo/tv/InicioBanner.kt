package br.com.saimo.tv

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Os títulos do banner do Início: os filmes e séries em alta na semana (TMDB), em português, com a arte larga.
 * Guardado por 6 horas; os gêneros, por 20 dias. Sem chave do TMDB ou sem rede, não há banner (nada é inventado).
 */
object InicioBanner {
    private const val VALE_MS = 6L * 3_600_000L
    private const val GENEROS_VALE_MS = 20L * 86_400_000L
    private const val QUANTOS = 5
    private const val LINGUA = "pt-BR"

    class Item(
        val serie: Boolean,
        val tmdb: Long,
        val titulo: String,
        val sinopse: String,
        val fundo: String,       // endereço completo da arte larga
        val ano: String,
        val generos: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject().put("s", serie).put("i", tmdb).put("t", titulo).put("o", sinopse)
            .put("f", fundo).put("a", ano).put("g", JSONArray(generos))

        companion object {
            fun deJson(o: JSONObject): Item {
                val g = o.optJSONArray("g")
                return Item(o.optBoolean("s"), o.optLong("i"), o.optString("t"), o.optString("o"), o.optString("f"),
                    o.optString("a"), List(g?.length() ?: 0) { g!!.optString(it) })
            }
        }
    }

    /** Os títulos do banner (do guardado, quando ainda vale). Lança erro só se não houver nada a mostrar. */
    suspend fun carregar(c: Context): List<Item> = withContext(Dispatchers.IO) {
        val arquivo = File(c.cacheDir, "inicio-banner.json")
        val guardado = lerGuardado(arquivo)
        if (guardado != null && System.currentTimeMillis() - guardado.first < VALE_MS) return@withContext guardado.second
        try {
            val lista = buscar(c)
            if (lista.isEmpty()) return@withContext guardado?.second.orEmpty()
            gravarGuardado(arquivo, lista)
            lista
        } catch (e: Exception) {
            guardado?.second ?: throw e
        }
    }

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

    private fun buscar(c: Context): List<Item> {
        val generos = generos(c)
        val a = tmdb("/trending/all/week?language=$LINGUA").optJSONArray("results") ?: return emptyList()
        val saida = ArrayList<Item>()
        for (i in 0 until a.length()) {
            if (saida.size >= QUANTOS) break
            val o = a.optJSONObject(i) ?: continue
            val tipo = o.optString("media_type")
            if (tipo != "movie" && tipo != "tv") continue
            val serie = tipo == "tv"
            val fundo = o.optString("backdrop_path")
            val sinopse = o.optString("overview").trim()
            val titulo = o.optString(if (serie) "name" else "title")
            val id = o.optLong("id")
            if (fundo.isEmpty() || fundo == "null" || sinopse.isEmpty() || titulo.isEmpty() || id <= 0) continue
            val data = o.optString(if (serie) "first_air_date" else "release_date")
            val ids = o.optJSONArray("genre_ids")
            val nomes = (0 until (ids?.length() ?: 0)).mapNotNull { generos["$tipo:${ids!!.optInt(it)}"] }.take(3)
            saida += Item(serie, id, titulo, sinopse, "https://image.tmdb.org/t/p/w1280$fundo", data.take(4), nomes)
        }
        return saida
    }

    /** Os nomes dos gêneros ("movie:28" → "Ação"), guardados por 20 dias. */
    private fun generos(c: Context): Map<String, String> {
        val f = File(c.cacheDir, "inicio-generos.json")
        try {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                if (System.currentTimeMillis() - o.optLong("em") < GENEROS_VALE_MS) {
                    val m = o.getJSONObject("g")
                    return m.keys().asSequence().associateWith { m.getString(it) }
                }
            }
        } catch (e: Exception) { /* busca de novo */ }
        val mapa = HashMap<String, String>()
        try {
            for (tipo in listOf("movie", "tv")) {
                val a = tmdb("/genre/$tipo/list?language=$LINGUA").optJSONArray("genres") ?: continue
                for (i in 0 until a.length()) {
                    val g = a.optJSONObject(i) ?: continue
                    mapa["$tipo:${g.optInt("id")}"] = g.optString("name")
                }
            }
            f.writeText(JSONObject().put("em", System.currentTimeMillis()).put("g", JSONObject(mapa as Map<*, *>)).toString())
        } catch (e: Exception) { /* sem gêneros, o banner mostra só o ano */ }
        return mapa
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
}
