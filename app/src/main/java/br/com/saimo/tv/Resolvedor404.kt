package br.com.saimo.tv

/**
 * Versão pessoal: sem buscadores de fontes embutidos (os endereços escondidos que vinham aqui
 * foram retirados). O app toca só as listas que a pessoa coloca em "Minhas listas".
 * Mantido com a mesma assinatura para não mexer em quem chama.
 */
object Resolvedor404 {
    @Suppress("UNUSED_PARAMETER")
    suspend fun fontes(
        tmdbId: Int,
        serie: Boolean,
        temporada: Int = 0,
        numero: Int = 0,
    ): Map<String, List<String>> = emptyMap()
}
