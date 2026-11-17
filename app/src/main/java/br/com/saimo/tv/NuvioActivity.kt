package br.com.saimo.tv

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * A página Nuvio (etapa 3): a marca, "Abrir Nuvio" e as fileiras Continuar assistindo e Minha Lista.
 * É uma camada de acesso: não tem player nem catálogo próprio, o Nuvio original é que toca.
 *
 * As fileiras vêm do Trakt da pessoa (que o Nuvio mantém sincronizado), com capas do TMDB; sem Trakt,
 * sem rede ou sem títulos, mostram o estado vazio. Nenhum título é inventado.
 */
@UnstableApi
class NuvioActivity : TelaComMenu() {

    override val semMenu = true

    private lateinit var menu: MenuLateral
    private var carregando = false
    private var ultimaCarga = 0L

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
            else Trakt.entrar(applicationContext) { runOnUiThread { if (!isDestroyed) { atualizarTrakt(); carregarTitulos() } } }
            atualizarTrakt()
        }
        for (id in listOf(R.id.nuvioContinuarFila, R.id.nuvioListaFila)) {
            findViewById<RecyclerView>(id).layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        }
        atualizarTrakt()
        carregarTitulos()
        focarQuandoPronto { abrir }
    }

    override fun onResume() {
        super.onResume()
        atualizarTrakt()
        carregarTitulos()
    }

    override fun onDestroy() {
        // Sair da página desiste de uma conexão pela metade; o login já feito fica.
        Trakt.cancelar()
        super.onDestroy()
    }

    /** Busca no Trakt (no máximo a cada minuto) o que a pessoa está assistindo e a lista dela. */
    private fun carregarTitulos() {
        if (carregando || !Trakt.conectado(this)) return
        if (System.currentTimeMillis() - ultimaCarga < 60_000L) return
        carregando = true
        lifecycleScope.launch {
            val continuar = try { NuvioDados.continuar(applicationContext) } catch (e: Exception) { emptyList() }
            val lista = try { NuvioDados.lista(applicationContext) } catch (e: Exception) { emptyList() }
            carregando = false
            if (isDestroyed) return@launch
            ultimaCarga = System.currentTimeMillis()
            mostrar(R.id.nuvioContinuarFila, R.id.nuvioContinuarVazio, continuar)
            mostrar(R.id.nuvioListaFila, R.id.nuvioListaVazio, lista)
        }
    }

    private fun mostrar(filaId: Int, vazioId: Int, titulos: List<NuvioDados.Titulo>) {
        val fila = findViewById<RecyclerView>(filaId)
        fila.visibility = if (titulos.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(vazioId).visibility = if (titulos.isEmpty()) View.VISIBLE else View.GONE
        fila.adapter = NuvioCapas(titulos, { abrirTitulo(it) }, { findViewById<View>(menu.primeiroId).requestFocus() })
    }

    /** OK num título: abre no Nuvio; se o link não resolver, só o aplicativo. Avisa quando não dá. */
    private fun abrirTitulo(t: NuvioDados.Titulo) {
        val aviso = findViewById<TextView>(R.id.nuvioAviso)
        val texto = when (Nuvio.abrirTitulo(this, t.serie, t.imdb, t.tmdb)) {
            Nuvio.Resultado.ABRIU -> null
            Nuvio.Resultado.SEM_APP -> getString(R.string.nuvio_sem_app)
            Nuvio.Resultado.FALHOU -> getString(R.string.nuvio_falha)
        }
        aviso.text = texto.orEmpty()
        aviso.visibility = if (texto == null) View.GONE else View.VISIBLE
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
