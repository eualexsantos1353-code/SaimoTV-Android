package br.com.saimo.tv

import java.text.Normalizer

/**
 * O que a consulta do Free My TV procura no guia (ConsultaDeCanal): o tipo de programa ("filme", "serie"...) pela
 * categoria que o guia escreve do jeito dele ("Filmes", "Movie", "Cinema"), e um título por um pedaço do nome. Kotlin
 * puro, sem Android: testado em JVM (ProcuraNoGuiaTest).
 */
internal object ProcuraNoGuia {
    private val ACENTOS = Regex("\\p{M}+")
    private val SEPARADOR = Regex("[^a-z0-9]+")

    /** Os tipos que se pode pedir e como os guias escrevem cada um. */
    val TIPOS: Map<String, List<String>> = mapOf(
        "filme" to listOf("filme", "filmes", "movie", "movies", "cinema", "longa"),
        "serie" to listOf("serie", "series", "seriado", "seriados"),
        "esporte" to listOf("esporte", "esportes", "futebol", "sport", "sports"),
        "noticias" to listOf("noticia", "noticias", "jornalismo", "telejornal", "jornal", "news"),
        "infantil" to listOf("infantil", "kids", "desenho", "desenhos", "criancas", "children"),
        "documentario" to listOf("documentario", "documentarios", "documentary"),
        "reality" to listOf("reality"),
        "variedades" to listOf("variedades", "entretenimento", "auditorio", "talk show"),
    )

    /** Sem acentos, minúsculo, palavras separadas por um espaço: "Séries / Drama" → "series drama" */
    fun limpo(texto: String): String =
        Normalizer.normalize(texto, Normalizer.Form.NFD).replace(ACENTOS, "").lowercase()
            .split(SEPARADOR).filter { it.isNotEmpty() }.joinToString(" ")

    /** A categoria do guia é desse tipo? Palavra inteira: "seriado" não é "serie" por acaso, "filmes" é "filme". */
    fun ehDoTipo(categoria: String, tipo: String): Boolean {
        val nomes = TIPOS[tipo] ?: return false
        val cat = " " + limpo(categoria) + " "
        if (cat.isBlank()) return false
        return nomes.any { cat.contains(" " + limpo(it) + " ") }
    }

    /** O título tem o pedido inteiro, em palavras inteiras: "harry potter" acha "Harry Potter e a Pedra Filosofal". */
    fun tituloTem(titulo: String, pedido: String): Boolean {
        val p = limpo(pedido)
        if (p.length < 2) return false
        return (" " + limpo(titulo) + " ").contains(" $p ")
    }
}
