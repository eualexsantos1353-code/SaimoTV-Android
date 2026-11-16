package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** O link saimo://canal: qual canal da lista o nome pedido abre, e como bateu. */
class BuscaDeCanalTest {

    private fun achar(nomes: List<String>, pedido: String) = BuscaDeCanal.encontrar(nomes, pedido)

    @Test
    fun `mesmo nome vale antes de tudo`() {
        val a = achar(listOf("ESPN 4", "ESPN"), "espn")!!
        assertEquals(1, a.indice)
        assertEquals(BuscaDeCanal.Tipo.IGUAL, a.tipo)
    }

    @Test
    fun `pedido com a qualidade abre essa versao`() {
        val a = achar(listOf("Band FHD", "BAND 4K [H265]"), "Band 4K")!!
        assertEquals(1, a.indice)
        assertEquals(BuscaDeCanal.Tipo.COMPLETO, a.tipo)
    }

    @Test
    fun `sem qualidade no pedido o 4K vem primeiro`() {
        val a = achar(listOf("Band HD", "Band FHD", "Band 4K"), "Band")!!
        assertEquals(2, a.indice)
        assertEquals(BuscaDeCanal.Tipo.BASE, a.tipo)
    }

    @Test
    fun `FHD antes de HD e de SD`() {
        assertEquals(1, achar(listOf("Band SD", "Band FHD", "Band HD"), "Band")!!.indice)
    }

    @Test
    fun `H265 depois do H264 da mesma qualidade`() {
        assertEquals(1, achar(listOf("Band 4K H265", "Band 4K"), "Band")!!.indice)
    }

    @Test
    fun `ignora acentos e etiquetas`() {
        val a = achar(listOf("Outro", "Cazé TV FHD (BR)"), "Caze TV")!!
        assertEquals(1, a.indice)
        assertEquals(BuscaDeCanal.Tipo.BASE, a.tipo)
    }

    @Test
    fun `so comeca com o pedido diz COMECO`() {
        val a = achar(listOf("ESPN 2", "ESPN 4 FHD"), "ESPN")!!
        assertEquals(1, a.indice)
        assertEquals(BuscaDeCanal.Tipo.COMECO, a.tipo)
    }

    @Test
    fun `pedaco do nome nao vale`() {
        assertNull(achar(listOf("SporTV2", "Premiere Clubes"), "SporTV"))
        assertNull(achar(listOf("SporTV"), "Premiere"))
    }

    @Test
    fun `pedido so de qualidade nao acha nada`() {
        assertNull(achar(listOf("Band FHD"), "FHD [H265]"))
    }
}
