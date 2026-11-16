package br.com.saimo.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** O tvg-id de cada canal (o código dele no guia da lista) é guardado: do M3U e da API Xtream. Sem rede. */
class TvgIdTest {

    @Test
    fun `M3U guarda o tvg-id e junta versoes do mesmo canal`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-id="hbo.br" tvg-name="HBO" group-title="Filmes",HBO
            http://exemplo.dev/live/1.ts
            #EXTINF:-1 tvg-id="" group-title="Filmes",HBO
            http://exemplo.dev/live/2.ts
            #EXTINF:-1 group-title="Abertos",Globo
            http://exemplo.dev/live/3.ts
        """.trimIndent()
        val canais = LeitorM3u.ler(m3u)
        assertEquals(listOf("HBO", "Globo"), canais.map { it.nome })
        assertEquals("hbo.br", canais[0].tvgId)
        assertEquals(2, canais[0].fontes.size)
        assertNull(canais[1].tvgId)
    }

    @Test
    fun `primeiro tvg-id informado vale quando a primeira versao nao tem`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1,Band
            http://exemplo.dev/live/1.ts
            #EXTINF:-1 tvg-id="band.br",Band
            http://exemplo.dev/live/2.ts
        """.trimIndent()
        assertEquals("band.br", LeitorM3u.ler(m3u).single().tvgId)
    }

    @Test
    fun `API Xtream guarda o epg_channel_id`() {
        val conta = Xtream.Conta("http://exemplo.dev", "u", "p")
        val streams = """[
            {"stream_id":10,"name":"HBO 1 4k","epg_channel_id":"hbo.br","category_id":"1"},
            {"stream_id":11,"name":"HBO 1 4k","epg_channel_id":null,"category_id":"1"},
            {"stream_id":12,"name":"Globo","category_id":"1"}
        ]"""
        val canais = Xtream.canaisDaApi(conta, streams, "[]")
        assertEquals("hbo.br", canais[0].tvgId)
        assertNull(canais[1].tvgId)
    }
}
