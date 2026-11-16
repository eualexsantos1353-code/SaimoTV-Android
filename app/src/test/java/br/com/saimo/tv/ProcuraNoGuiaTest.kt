package br.com.saimo.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcuraNoGuiaTest {

    @Test
    fun `categoria do guia em varios jeitos`() {
        assertTrue(ProcuraNoGuia.ehDoTipo("Filmes", "filme"))
        assertTrue(ProcuraNoGuia.ehDoTipo("Movie / Action", "filme"))
        assertTrue(ProcuraNoGuia.ehDoTipo("Séries", "serie"))
        assertTrue(ProcuraNoGuia.ehDoTipo("Jornalismo", "noticias"))
        assertTrue(ProcuraNoGuia.ehDoTipo("Desenhos Animados", "infantil"))
    }

    @Test
    fun `palavra inteira e tipo desconhecido`() {
        assertFalse(ProcuraNoGuia.ehDoTipo("Filmagem de bastidores", "filme"))
        assertFalse(ProcuraNoGuia.ehDoTipo("", "filme"))
        assertFalse(ProcuraNoGuia.ehDoTipo("Filmes", "culinaria"))
    }

    @Test
    fun `titulo por um pedaco do nome`() {
        assertTrue(ProcuraNoGuia.tituloTem("Harry Potter e a Pedra Filosofal", "harry potter"))
        assertTrue(ProcuraNoGuia.tituloTem("Duna: Parte Dois", "Duna"))
        assertFalse(ProcuraNoGuia.tituloTem("Dunas do Saara", "Duna"))
        assertFalse(ProcuraNoGuia.tituloTem("Qualquer", "a"))
    }
}
