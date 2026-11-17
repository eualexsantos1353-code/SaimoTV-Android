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

        val traktBotao = findViewById<TextView>(R.id.nuvioTraktBotao)
        traktBotao.nextFocusLeftId = menu.primeiroId
        traktBotao.setOnClickListener {
            if (Trakt.conectando) Trakt.cancelar()
            else Trakt.entrar(applicationContext) { runOnUiThread { if (!isDestroyed) atualizarTrakt() } }
            atualizarTrakt()
        }
        atualizarTrakt()
        focarQuandoPronto { abrir }
    }

    override fun onResume() {
        super.onResume()
        atualizarTrakt()
    }

    override fun onDestroy() {
        // Sair da página desiste de uma conexão pela metade; o login já feito fica.
        Trakt.cancelar()
        super.onDestroy()
    }

    /** O bloco do Trakt: indisponível, conectado, esperando o código ou pedindo a conexão. */
    private fun atualizarTrakt() {
        val texto = findViewById<TextView>(R.id.nuvioTraktTexto)
        val codigo = findViewById<TextView>(R.id.nuvioTraktCodigo)
        val botao = findViewById<TextView>(R.id.nuvioTraktBotao)
        codigo.visibility = View.GONE
        botao.visibility = View.VISIBLE
        when {
            !Trakt.disponivel() -> {
                texto.text = getString(R.string.nuvio_trakt_indisponivel)
                botao.visibility = View.GONE
            }
            Trakt.conectado(this) -> {
                val nome = Trakt.usuario(this)
                texto.text = if (nome.isEmpty()) getString(R.string.nuvio_trakt_conectado_sem)
                    else getString(R.string.nuvio_trakt_conectado, nome)
                botao.visibility = View.GONE
            }
            Trakt.conectando -> {
                val c = Trakt.codigo
                texto.text = getString(R.string.nuvio_trakt_aguardando, Trakt.endereco)
                codigo.text = c.orEmpty()
                codigo.visibility = if (c == null) View.GONE else View.VISIBLE
                botao.text = getString(R.string.nuvio_trakt_cancelar)
            }
            else -> {
                val m = Trakt.mensagem
                texto.text = if (m.isEmpty()) getString(R.string.nuvio_trakt_explica) else m
                botao.text = getString(if (m.isEmpty()) R.string.nuvio_trakt_conectar else R.string.nuvio_trakt_tentar)
            }
        }
        // O botão sumiu com o foco nele: o foco volta para "Abrir Nuvio".
        if (botao.visibility != View.VISIBLE && botao.hasFocus()) findViewById<View>(R.id.nuvioAbrir).requestFocus()
    }
}
