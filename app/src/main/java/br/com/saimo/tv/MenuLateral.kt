package br.com.saimo.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O menu lateral do Início.
 *
 * Fechado, são só os ícones (uma faixa estreita à esquerda). Com o foco nele,
 * abre POR CIMA do conteúdo e mostra os nomes: o conteúdo não se mexe, então
 * nada é medido de novo a cada passo — o que pesa num TV Box fraco.
 *
 * Só o Início usa este menu. As outras telas seguem com a barra do topo
 * (MenuGlobal), que não foi tocada.
 */
class MenuLateral(
    private val contexto: Context,
    private val caixa: LinearLayout,
    itens: List<Item>,
    destinoDireita: View,
    private val aoFocar: () -> Unit,
) {
    /** Um destino do menu. [atual] marca a tela em que a pessoa está. */
    class Item(val texto: Int, val icone: Int, val atual: Boolean = false, val abrir: () -> Unit)

    private val d = contexto.resources.displayMetrics.density
    private val linhas = mutableListOf<View>()
    private val nomes = mutableListOf<TextView>()
    private var aberto = false

    /** O primeiro item: para onde a seta para a esquerda leva, vinda do conteúdo. */
    val primeiroId: Int

    init {
        caixa.removeAllViews()
        for (item in itens) {
            val nome = TextView(contexto).apply {
                text = contexto.getString(item.texto)
                setTextColor(Color.WHITE)
                textSize = 16f
                maxLines = 1
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (14 * d).toInt() }
            }
            val icone = ImageView(contexto).apply {
                setImageResource(item.icone)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt())
            }
            val linha = LinearLayout(contexto).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isSelected = item.atual
                setBackgroundResource(R.drawable.bg_rail_item)
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt())
                    .apply { topMargin = (4 * d).toInt(); bottomMargin = (4 * d).toInt() }
                addView(icone)
                addView(nome)
                nextFocusRightId = destinoDireita.id
                setOnClickListener { item.abrir() }
                setOnFocusChangeListener { _, foco ->
                    if (foco) {
                        definir(true)
                        aoFocar()
                    } else {
                        // Entre um item e outro o foco passa por "ninguém": só fecha se saiu do menu.
                        caixa.post { if (!caixa.hasFocus()) definir(false) }
                    }
                }
            }
            caixa.addView(linha)
            linhas += linha
            nomes += nome
        }
        // Nas pontas, para cima e para baixo o foco não escapa do menu.
        linhas.firstOrNull()?.let { it.nextFocusUpId = it.id }
        linhas.lastOrNull()?.let { it.nextFocusDownId = it.id }
        primeiroId = linhas.first().id
    }

    /** Leva o foco ao menu, no primeiro item. */
    fun focar() {
        linhas.firstOrNull()?.requestFocus()
    }

    private fun definir(abrir: Boolean) {
        if (abrir == aberto) return
        aberto = abrir
        for (nome in nomes) nome.visibility = if (abrir) View.VISIBLE else View.GONE
        caixa.setBackgroundColor(if (abrir) 0xF2121116.toInt() else 0)
        caixa.layoutParams = caixa.layoutParams.apply { width = ((if (abrir) 220 else 64) * d).toInt() }
    }
}
