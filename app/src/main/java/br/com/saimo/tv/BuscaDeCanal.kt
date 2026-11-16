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
    private val FULL_HD = setOf("fhd", "fullhd")
    private val ULTRA = setOf("4k", "uhd")

    private fun palavras(nome: String): List<String> =
        Normalizer.normalize(nome, Normalizer.Form.NFD).replace(ACENTOS, "").lowercase()
            .replace(ETIQUETAS, " ").split(SEPARADOR).filter { it.isNotEmpty() }

    fun base(nome: String): String = palavras(nome).filter { it !in QUALIDADE }.joinToString(" ")

    /** Nome inteiro comparável, com a qualidade: "Band 4K" → "band 4k" (pedido de versão exata) */
    fun completo(nome: String): String = palavras(nome).joinToString(" ")

    /**
     * Versão preferida quando o pedido não diz a qualidade: 4K (0), FHD (1), HD (2), sem marca (3),
     * SD (4). Para abrir outra versão, peça com a qualidade no nome (saimo://canal?n=Band%20FHD).
     */
    fun qualidade(nome: String): Int {
        val p = palavras(nome)
        return when {
            p.any { it in ULTRA } -> 0
            p.any { it in FULL_HD } -> 1
            "hd" in p -> 2
            "sd" in p -> 4
            else -> 3
        }
    }

    /** Versão H.265 vai depois da H.264 do mesmo canal */
    fun hevc(nome: String): Int = if (palavras(nome).any { it == "h265" || it == "hevc" }) 1 else 0
}
