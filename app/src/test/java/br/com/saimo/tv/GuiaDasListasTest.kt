package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** O guia das Minhas listas: o link do guia da conta e a grade dividida entre as versões do canal. Sem rede. */
class GuiaDasListasTest {

    @Test
    fun `guia da conta Xtream no mesmo servidor`() {
        val conta = Xtream.Conta("http://exemplo.dev:8080", "ana b", "s@nha")
        assertEquals("http://exemplo.dev:8080/xmltv.php?username=ana%20b&password=s%40nha", Xtream.guia(conta))
    }

    @Test
    fun `a grade vai para todas as versoes do canal e a primeira vale`() {
        val hbo = listOf(Programme("Duna", "Filmes", 1L, 2L))
        val outra = listOf(Programme("Outro", "Filmes", 1L, 2L))
        val destino = LinkedHashMap<String, List<Programme>>()
        destino["Band"] = outra
        val porId = mapOf("hbo.br" to listOf("HBO 1 4k", "HBO 1 FHD"), "band.br" to listOf("Band"), "sem.grade" to listOf("X"))
        Epg.juntarPorCodigo(destino, porId, mapOf("HBO 1 4k" to hbo, "Band" to hbo))
        assertSame(hbo, destino["HBO 1 4k"])
        assertSame(hbo, destino["HBO 1 FHD"])
        assertSame(outra, destino["Band"])
        assertNull(destino["X"])
    }
}
