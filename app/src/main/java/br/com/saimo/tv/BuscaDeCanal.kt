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

    /** Como o nome pedido bateu com o canal da lista, do mais certo ao menos certo. */
    enum class Tipo {
        /** O mesmo nome, só com maiúsculas diferentes */
        IGUAL,
        /** O mesmo nome com a qualidade ("Band 4K" acha "BAND 4K [H265]") */
        COMPLETO,
        /** O mesmo canal em outra qualidade ("Band" acha "BAND 4K") */
        BASE,
        /** Só um canal que começa com o pedido ("Band" acha "Band SP"): pode ser outro canal */
        COMECO,
    }

    /** O canal achado: a posição dele em `nomes` e como bateu. */
    class Achado(val indice: Int, val tipo: Tipo)

    /**
     * O canal pedido pelo nome (link saimo://canal?n=…) entre os [nomes] da lista, na ordem da tela, ou
     * null. Ignora acentos, maiúsculas e qualidade; entre versões do mesmo canal fica com a preferida por
     * [qualidade] e deixa H.265 por último. Com a qualidade no pedido ("Band 4K"), essa versão. Sem nome
     * igual, aceita um que comece com o pedido ("Band" → "Band SP"), e diz isso no [Tipo].
     */
    fun encontrar(nomes: List<String>, pedido: String): Achado? {
        val exato = nomes.indexOfFirst { it.equals(pedido, ignoreCase = true) }
        if (exato >= 0) return Achado(exato, Tipo.IGUAL)
        // Pedido com a qualidade ("Band 4K"): a versão exata, se existir
        val inteiro = completo(pedido)
        val mesmoInteiro = nomes.indexOfFirst { completo(it) == inteiro }
        if (inteiro.isNotEmpty() && mesmoInteiro >= 0) return Achado(mesmoInteiro, Tipo.COMPLETO)
        val alvo = base(pedido)
        if (alvo.isEmpty()) return null
        val bases = nomes.map { base(it) }
        fun melhor(indices: List<Int>): Int = indices.minWithOrNull(compareBy<Int>(
            { qualidade(nomes[it]) },
            { hevc(nomes[it]) },
            { nomes[it].length },
        )) ?: -1
        val iguais = bases.indices.filter { bases[it] == alvo }
        if (iguais.isNotEmpty()) return Achado(melhor(iguais), Tipo.BASE)
        val comecam = bases.indices.filter { bases[it].startsWith("$alvo ") }
        if (comecam.isNotEmpty()) return Achado(melhor(comecam), Tipo.COMECO)
        return null
    }
}
