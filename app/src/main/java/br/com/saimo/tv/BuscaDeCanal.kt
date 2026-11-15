package br.com.saimo.tv

import java.text.Normalizer

/**
 * Nome de canal comparável (link saimo://canal?n=…): sem acentos, maiúsculas, etiquetas entre
 * colchetes/parênteses e palavras de qualidade. "BAND SP FHD [H265]" → "band sp".
 */
internal object BuscaDeCanal {
    private val ACENTOS = Regex("\\p{M}+")
    private val ETIQUETAS = Regex("\\[[^\\]]*\\]|\\([^)]*\\)")
    private val SEPARADOR = Regex("[^a-z0-9+]+")
    private val QUALIDADE = setOf("fhd", "hd", "sd", "uhd", "4k", "fullhd", "h265", "h264", "hevc", "alt")
    private val ALTA = setOf("fhd", "fullhd", "4k", "uhd")

    private fun palavras(nome: String): List<String> =
        Normalizer.normalize(nome, Normalizer.Form.NFD).replace(ACENTOS, "").lowercase()
            .replace(ETIQUETAS, " ").split(SEPARADOR).filter { it.isNotEmpty() }

    fun base(nome: String): String = palavras(nome).filter { it !in QUALIDADE }.joinToString(" ")

    /** FHD/4K primeiro (0), depois HD (1), sem marca (2), SD por último (3) */
    fun qualidade(nome: String): Int {
        val p = palavras(nome)
        return when {
            p.any { it in ALTA } -> 0
            "hd" in p -> 1
            "sd" in p -> 3
            else -> 2
        }
    }

    /** Versão H.265 vai depois da H.264 do mesmo canal */
    fun hevc(nome: String): Int = if (palavras(nome).any { it == "h265" || it == "hevc" }) 1 else 0
}
