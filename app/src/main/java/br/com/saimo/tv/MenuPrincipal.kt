package br.com.saimo.tv

import android.app.Activity
import android.content.Intent
import androidx.media3.common.util.UnstableApi

/** Em que tela do menu lateral a pessoa está. */
enum class Destino { INICIO, NUVIO }

/**
 * Os itens do menu lateral, num lugar só: o Início e a página Nuvio mostram o mesmo menu.
 * Fora do Início, a tela atual se fecha ao ir para outra: o Início fica sempre por baixo e
 * VOLTAR nunca vira um labirinto.
 */
@UnstableApi
object MenuPrincipal {

    fun itens(tela: Activity, atual: Destino, noInicio: () -> Unit): List<MenuLateral.Item> {
        fun ir(intent: Intent) {
            tela.startActivity(intent)
            if (atual != Destino.INICIO) tela.finish()
        }
        fun ir(destino: Class<*>) = ir(Intent(tela, destino))
        return listOf(
            MenuLateral.Item(R.string.menu_inicio, R.drawable.ic_home, atual = atual == Destino.INICIO) {
                if (atual == Destino.INICIO) noInicio() else tela.finish()
            },
            MenuLateral.Item(R.string.menu_ao_vivo, R.drawable.ic_live_tv) { ir(AoVivoActivity::class.java) },
            MenuLateral.Item(R.string.menu_nuvio, R.drawable.ic_nuvio, atual = atual == Destino.NUVIO) {
                if (atual != Destino.NUVIO) ir(NuvioActivity::class.java)
            },
            MenuLateral.Item(R.string.menu_eventos, R.drawable.ic_football) { ir(EventosActivity::class.java) },
            MenuLateral.Item(R.string.menu_on_demand, R.drawable.ic_movie) { ir(OnDemandActivity::class.java) },
            MenuLateral.Item(R.string.vod_favoritos, R.drawable.ic_star) { ir(GradeActivity.intent(tela, GradeActivity.FAVORITOS)) },
            MenuLateral.Item(R.string.menu_radios, R.drawable.ic_radio) { ir(RadiosActivity::class.java) },
            MenuLateral.Item(R.string.menu_ajustes, R.drawable.ic_settings) { ir(AjustesActivity::class.java) },
        )
    }
}
