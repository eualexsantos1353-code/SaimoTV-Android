package br.com.saimo.tv

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Central de Notificações (dados): avisos de episódio novo e de filme disponível.
 *
 * A fonte é a Agenda ([AgendaDados]), só o que vem da conta do Trakt da pessoa (séries que ela segue e filmes
 * das listas dela). Um episódio vira aviso quando o horário de estreia dele chega; um filme, quando chega o dia.
 * Nada é inventado, e sem Trakt conectado não há avisos.
 *
 * Cada aviso guarda a data/hora de recebimento e some 72 horas depois, estando visto ou não. Os avisos ficam
 * no armazenamento do aparelho (sobrevivem a reiniciar a TV) e, ao ler, os vencidos são apagados. Um mesmo
 * lançamento não vira aviso duas vezes, nem depois de o aviso expirar.
 *
 * Só olha a Agenda quando o app está aberto ([atualizar]); não há serviço em segundo plano.
 */
object Notificacoes {
    const val PRAZO_MS = 72L * 3_600_000L
    private const val DIA = 86_400_000L
    private const val PREFS = "astral_notificacoes"

    class Aviso(
        val id: String,
        val serie: Boolean,
        val titulo: String,
        val poster: String?,      // caminho do pôster no TMDB (ou nulo)
        val tmdb: Long,
        val temporada: Int,
        val numero: Int,
        val recebida: Long,
        var lida: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("s", serie).put("t", titulo).put("p", poster ?: "")
            .put("m", tmdb).put("te", temporada).put("n", numero).put("r", recebida).put("l", lida)

        companion object {
            fun deJson(o: JSONObject) = Aviso(
                o.getString("id"), o.optBoolean("s"), o.optString("t"), o.optString("p").ifEmpty { null },
                o.optLong("m"), o.optInt("te"), o.optInt("n"), o.getLong("r"), o.optBoolean("l"),
            )
        }
    }

    // MARK: - Leitura

    /** Os avisos valem ainda (os vencidos já foram apagados), do mais recente para o mais antigo. */
    @Synchronized
    fun ativos(c: Context, agora: Long = System.currentTimeMillis()): List<Aviso> {
        val (avisos, vistos) = ler(c)
        if (descartar(avisos, vistos, agora)) gravar(c, avisos, vistos)
        return avisos.sortedByDescending { it.recebida }
    }

    /** Quantos avisos ainda não foram vistos (o número do sino). */
    fun naoLidas(c: Context): Int = ativos(c).count { !it.lida }

    /** O aviso foi visto: sai da contagem do sino, mas continua na lista até o prazo. */
    @Synchronized
    fun marcarLida(c: Context, id: String) {
        val (avisos, vistos) = ler(c)
        val a = avisos.firstOrNull { it.id == id } ?: return
        if (a.lida) return
        a.lida = true
        gravar(c, avisos, vistos)
    }

    /** "Agora", "Há 25 min", "Há 3h" ou "Há 2 dias". */
    fun tempo(c: Context, recebida: Long, agora: Long = System.currentTimeMillis()): String {
        val min = ((agora - recebida) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> c.getString(R.string.ntf_agora)
            min < 60 -> c.getString(R.string.ntf_min, min.toInt())
            min < 24 * 60 -> c.getString(R.string.ntf_horas, (min / 60).toInt())
            else -> c.getString(R.string.ntf_dias, (min / (24 * 60)).toInt())
        }
    }

    // MARK: - Novos avisos

    /** Olha a Agenda (a do dia, guardada, ou busca se venceu) e cria os avisos novos. Devolve quantos criou. */
    suspend fun atualizar(c: Context): Int {
        val itens = try {
            AgendaDados.carregar(c.applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgendaDados.guardada(c.applicationContext)
        }
        return ingerir(c, itens)
    }

    /**
     * Cria um aviso para cada item da conta que já chegou e ainda está dentro das 72 horas.
     * Episódio com hora: chegou na hora dele. Filme (só o dia é conhecido): chegou no dia; o recebimento
     * é o primeiro momento em que o vimos nesse dia.
     */
    @Synchronized
    fun ingerir(c: Context, itens: List<AgendaDados.Item>, agora: Long = System.currentTimeMillis()): Int {
        val (avisos, vistos) = ler(c)
        var mudou = descartar(avisos, vistos, agora)
        var novos = 0
        for (it in itens) {
            if (!it.meu || it.em <= 0 || it.em > agora) continue
            val fim = if (it.comHora) it.em else it.em + DIA - 1   // o último instante do dia do lançamento
            if (fim < agora - PRAZO_MS) continue
            val id = if (it.serie) "tv:${it.id}:${it.temporada}:${it.numero}" else "movie:${it.id}"
            if (vistos.has(id)) continue
            val recebida = if (it.comHora) it.em else agora.coerceIn(it.em, it.em + DIA - 1)
            avisos += Aviso(id, it.serie, it.titulo, it.poster, it.id, it.temporada, it.numero, recebida, false)
            vistos.put(id, fim)
            novos++
            mudou = true
        }
        if (mudou) gravar(c, avisos, vistos)
        return novos
    }

    // MARK: - Armazenamento

    /** Apaga os avisos com 72 horas completas e esquece os lançamentos que não voltam mais a aparecer. */
    private fun descartar(avisos: MutableList<Aviso>, vistos: JSONObject, agora: Long): Boolean {
        var mudou = avisos.removeAll { agora - it.recebida >= PRAZO_MS }
        // A margem de uma hora garante que o lançamento já saiu da janela de [ingerir] antes de ser esquecido.
        val velhos = vistos.keys().asSequence().filter { vistos.optLong(it) < agora - PRAZO_MS - 3_600_000L }.toList()
        for (k in velhos) { vistos.remove(k); mudou = true }
        return mudou
    }

    private fun ler(c: Context): Pair<MutableList<Aviso>, JSONObject> {
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val avisos = ArrayList<Aviso>()
        try {
            val a = JSONArray(p.getString("avisos", "[]"))
            for (i in 0 until a.length()) avisos += Aviso.deJson(a.getJSONObject(i))
        } catch (e: Exception) { avisos.clear() }
        val vistos = try { JSONObject(p.getString("vistos", "{}")) } catch (e: Exception) { JSONObject() }
        return avisos to vistos
    }

    private fun gravar(c: Context, avisos: List<Aviso>, vistos: JSONObject) {
        val a = JSONArray()
        avisos.forEach { a.put(it.toJson()) }
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("avisos", a.toString()).putString("vistos", vistos.toString()).apply()
    }
}
