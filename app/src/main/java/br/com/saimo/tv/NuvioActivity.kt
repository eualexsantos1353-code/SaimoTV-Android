package br.com.saimo.tv

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.media3.common.util.UnstableApi

/**
 * A página Nuvio (etapa 3): a marca, "Abrir Nuvio" e as fileiras Continuar assistindo e Minha Lista.
 * É uma camada de acesso: não tem player nem catálogo próprio, o Nuvio original é que toca.
 *
 * Nesta primeira parte (3a) as fileiras ainda não têm fonte de dados e mostram o estado vazio.
 * Nenhum título é inventado.
 */
@UnstableApi
class NuvioActivity : TelaComMenu() {

    override val semMenu = true

    private lateinit var menu: MenuLateral

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nuvio)

        val abrir = findViewById<View>(R.id.nuvioAbrir)
        val aviso = findViewById<TextView>(R.id.nuvioAviso)
        menu = MenuLateral(this, findViewById(R.id.nuvioMenu), MenuPrincipal.itens(this, Destino.NUVIO) {}, abrir) {}
        abrir.nextFocusLeftId = menu.primeiroId

        abrir.setOnClickListener {
            val texto = when (Nuvio.abrir(this)) {
                Nuvio.Resultado.ABRIU -> null
                Nuvio.Resultado.SEM_APP -> getString(R.string.nuvio_sem_app)
                Nuvio.Resultado.FALHOU -> getString(R.string.nuvio_falha)
            }
            aviso.text = texto.orEmpty()
            aviso.visibility = if (texto == null) View.GONE else View.VISIBLE
        }
        focarQuandoPronto { abrir }
    }
}
