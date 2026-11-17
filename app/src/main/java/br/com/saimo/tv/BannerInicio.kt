package br.com.saimo.tv

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * O banner grande do Início: um filme ou série em alta, com a arte larga, a sinopse e "Assistir agora" (abre no Nuvio).
 * Troca sozinho a cada 9 s; com o foco no botão, direita/esquerda trocam à mão. Sem dados, o banner some.
 */
class BannerInicio(
    private val tela: Activity,
    private val escopo: CoroutineScope,
    private val raiz: View,
    private val aoCarregar: () -> Unit,
) {
    private val fundo: ImageView = raiz.findViewById(R.id.bannerFundo)
    private val tag: TextView = raiz.findViewById(R.id.bannerTag)
    private val titulo: TextView = raiz.findViewById(R.id.bannerTitulo)
    private val meta: TextView = raiz.findViewById(R.id.bannerMeta)
    private val sinopse: TextView = raiz.findViewById(R.id.bannerSinopse)
    private val assistir: View = raiz.findViewById(R.id.inicioBannerAssistir)
    private val pontos: LinearLayout = raiz.findViewById(R.id.bannerPontos)
    private val handler = Handler(Looper.getMainLooper())
    private var itens: List<InicioBanner.Item> = emptyList()
    private var atual = 0
    private var rodando = false

    private val girar = object : Runnable {
        override fun run() {
            if (itens.size > 1) mostrar((atual + 1) % itens.size)
            if (rodando) handler.postDelayed(this, TROCA_MS)
        }
    }

    init {
        Cartoes.arredondar(raiz)
        assistir.setOnClickListener { abrir() }
        assistir.setOnKeyListener { _, codigo, evento ->
            if (evento.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                codigo == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (itens.size > 1) { mostrar((atual + 1) % itens.size); reiniciar() }
                    true
                }
                codigo == KeyEvent.KEYCODE_DPAD_LEFT && atual > 0 -> { mostrar(atual - 1); reiniciar(); true }
                else -> false
            }
        }
    }

    fun carregar() {
        escopo.launch(semDerrubar) {
            val lista = try { InicioBanner.carregar(tela.applicationContext) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (tela.isDestroyed) return@launch
            if (lista.isEmpty()) {
                if (itens.isEmpty()) raiz.visibility = View.GONE
                return@launch
            }
            itens = lista
            raiz.visibility = View.VISIBLE
            montarPontos()
            mostrar(0)
            assistir.visibility = View.VISIBLE
            retomar()
            aoCarregar()
        }
    }

    /** A tela voltou à frente: a troca automática continua. */
    fun retomar() {
        if (rodando || itens.size < 2) return
        rodando = true
        handler.postDelayed(girar, TROCA_MS)
    }

    /** A tela saiu da frente: nada de timer rodando por baixo. */
    fun pausar() {
        rodando = false
        handler.removeCallbacks(girar)
    }

    private fun reiniciar() {
        handler.removeCallbacks(girar)
        if (rodando) handler.postDelayed(girar, TROCA_MS)
    }

    private fun mostrar(i: Int) {
        val t = itens.getOrNull(i) ?: return
        atual = i
        fundo.load(t.fundo) { crossfade(true) }
        tag.text = tela.getString(if (t.serie) R.string.astral_tag_serie else R.string.astral_tag_filme)
        titulo.text = t.titulo
        meta.text = (listOf(t.ano) + t.generos).filter { it.isNotBlank() }.joinToString("  •  ")
        sinopse.text = t.sinopse
        for (n in 0 until pontos.childCount) pontos.getChildAt(n).alpha = if (n == i) 1f else 0.35f
    }

    private fun montarPontos() {
        pontos.removeAllViews()
        if (itens.size < 2) return
        val d = tela.resources.displayMetrics.density
        for (n in itens.indices) {
            pontos.addView(View(tela).apply {
                setBackgroundResource(R.drawable.ntf_ponto)
                layoutParams = LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).apply { marginStart = (6 * d).toInt() }
            })
        }
    }

    private fun abrir() {
        val t = itens.getOrNull(atual) ?: return
        when (Nuvio.abrirTitulo(tela, t.serie, null, t.tmdb)) {
            Nuvio.Resultado.ABRIU -> Unit
            Nuvio.Resultado.SEM_APP -> Toast.makeText(tela, R.string.nuvio_sem_app, Toast.LENGTH_LONG).show()
            Nuvio.Resultado.FALHOU -> Toast.makeText(tela, R.string.nuvio_falha, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TROCA_MS = 9_000L
    }
}
