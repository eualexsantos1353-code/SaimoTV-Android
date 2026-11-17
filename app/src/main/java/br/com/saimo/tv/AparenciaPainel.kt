package br.com.saimo.tv

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil3.load

/**
 * A seção Aparência de Configurações: Tema (com os horários do Automático), Plano de fundo (os 4 wallpapers),
 * Liquid Glass e Animações. Tudo grava em [Tema], que já aplica o resultado a todas as telas.
 *
 * Mudar o wallpaper, o Liquid Glass ou as animações recria a tela; por isso cada escolha avisa
 * ([aoMudar]) qual item tinha o foco, para a tela devolver o foco a ele depois.
 */
class AparenciaPainel(
    private val ctx: Context,
    private val conteudo: LinearLayout,
    private val idEsquerda: Int,
    private val escolherHora: (String, Int, (Int) -> Unit) -> Unit,
    private val trava: (Boolean) -> Unit,
    private val aoMudar: (String) -> Unit,
) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun str(id: Int) = ctx.getString(id)

    private fun cor(atributo: Int): Int {
        val v = TypedValue()
        ctx.theme.resolveAttribute(atributo, v, true)
        return v.data
    }

    fun montar() {
        // Remover o item que tem o foco faz o foco cair em outro lugar; a tela precisa saber que isso é só a montagem.
        trava(true)
        try {
            conteudo.removeAllViews()
            tema()
            wallpapers()
            vidro()
            animacoes()
        } finally {
            trava(false)
        }
    }

    /** O foco vai ao item do tema escolhido (ou ao primeiro). */
    fun focarPrimeiro() {
        conteudo.post { conteudo.findViewWithTag<View>("tema:" + Tema.modo.chave)?.requestFocus() }
    }

    private fun focar(chave: String) {
        conteudo.post { conteudo.findViewWithTag<View>(chave)?.requestFocus() }
    }

    // MARK: - Blocos

    private fun texto(t: String, sp: Float, c: Int, medio: Boolean = false): TextView = TextView(ctx).apply {
        text = t
        textSize = sp
        setTextColor(c)
        if (medio) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun cartao(titulo: String, dica: String): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.aj_cartao)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        c.addView(texto(titulo, 19f, cor(R.attr.astralTexto), true))
        c.addView(texto(dica, 14f, cor(R.attr.astralTextoSecundario)).apply { setPadding(0, dp(2), 0, dp(10)) })
        conteudo.addView(c)
        return c
    }

    private fun fileira(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun chip(chave: String, rotulo: String, marcado: Boolean, primeiro: Boolean, acao: () -> Unit): TextView =
        TextView(ctx).apply {
            tag = chave
            text = rotulo
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColorStateList(ctx, R.color.agenda_texto_pilula))
            gravity = Gravity.CENTER
            isFocusable = true
            isSelected = marcado
            setBackgroundResource(R.drawable.aj_chip)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) }
            if (primeiro) nextFocusLeftId = idEsquerda
            setOnClickListener {
                aoMudar(chave)
                acao()
                montar()
                focar(chave)
            }
        }

    private fun <T> opcoes(c: LinearLayout, prefixo: String, valores: List<T>, atual: T,
                           chave: (T) -> String, rotulo: (T) -> String, aplicar: (T) -> Unit) {
        val l = fileira()
        c.addView(l)
        valores.forEachIndexed { i, v ->
            l.addView(chip(prefixo + ":" + chave(v), rotulo(v), v == atual, i == 0) { aplicar(v) })
        }
    }

    // MARK: - Tema

    private fun rotuloModo(m: Tema.Modo) = str(when (m) {
        Tema.Modo.CLARO -> R.string.aj_tema_claro
        Tema.Modo.ESCURO -> R.string.aj_tema_escuro
        Tema.Modo.AUTO -> R.string.aj_tema_auto
    })

    private fun tema() {
        val c = cartao(str(R.string.aj_tema), str(R.string.aj_tema_dica))
        opcoes(c, "tema", Tema.Modo.values().toList(), Tema.modo, { it.chave }, { rotuloModo(it) }) { Tema.modo = it }
        if (!Tema.CLARO_PRONTO && Tema.modo != Tema.Modo.ESCURO) {
            c.addView(texto(str(R.string.aj_tema_aviso), 13f, cor(R.attr.astralTextoSutil)).apply { setPadding(0, dp(10), 0, 0) })
        }
        if (Tema.modo == Tema.Modo.AUTO) {
            val l = fileira().apply { setPadding(0, dp(12), 0, 0) }
            c.addView(l)
            l.addView(horario("hclaro", R.string.aj_ativar_claro, Tema.claroAs, true))
            l.addView(horario("hescuro", R.string.aj_ativar_escuro, Tema.escuroAs, false))
        }
    }

    private fun hora(minutos: Int) = "%02d:%02d".format(minutos / 60, minutos % 60)

    private fun horario(chave: String, titulo: Int, minutos: Int, claro: Boolean): View {
        val v = LinearLayout(ctx)
        v.tag = chave
        v.orientation = LinearLayout.VERTICAL
        v.isFocusable = true
        v.setBackgroundResource(R.drawable.aj_linha)
        v.setPadding(dp(16), dp(10), dp(16), dp(10))
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        if (claro) v.nextFocusLeftId = idEsquerda
        v.addView(texto(str(titulo), 13f, cor(R.attr.astralTextoSecundario)))
        v.addView(texto(hora(minutos), 26f, cor(R.attr.astralTexto), true))
        v.setOnClickListener {
            aoMudar(chave)
            escolherHora(str(titulo), minutos) { novo ->
                if (claro) Tema.claroAs = novo else Tema.escuroAs = novo
                montar()
                focar(chave)
            }
        }
        return v
    }

    // MARK: - Wallpapers

    private fun wallpapers() {
        val c = cartao(str(R.string.aj_fundo), str(R.string.aj_fundo_dica))
        val l = fileira()
        c.addView(l)
        Tema.Fundo.values().forEachIndexed { i, f -> l.addView(miniatura(f, i == 0)) }
    }

    private fun miniatura(f: Tema.Fundo, primeira: Boolean): View {
        val marcado = Tema.fundo == f
        val chave = "fundo:" + f.chave
        val coluna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        }
        val moldura = FrameLayout(ctx).apply {
            tag = chave
            isFocusable = true
            isSelected = marcado
            setBackgroundResource(R.drawable.aj_miniatura)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            if (primeira) nextFocusLeftId = idEsquerda
            setOnClickListener {
                aoMudar(chave)
                Tema.fundo = f
                montar()
                focar(chave)
            }
        }
        val imagem = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(9).toFloat())
                }
            }
            // O arquivo original, sem alteração; o Coil o reduz ao tamanho da miniatura em vez de decodificar inteiro.
            load(Tema.wallpaper(f))
        }
        moldura.addView(imagem)
        coluna.addView(moldura, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        coluna.addView(texto(f.rotulo, 14f,
            if (marcado) cor(R.attr.astralAccent) else cor(R.attr.astralTextoSecundario), marcado)
            .apply { setPadding(dp(4), dp(6), 0, 0) })
        return coluna
    }

    // MARK: - Liquid Glass e animações

    private fun vidro() {
        val c = cartao(str(R.string.aj_vidro), str(R.string.aj_vidro_dica))
        opcoes(c, "vidro", Tema.Vidro.values().toList(), Tema.vidro, { it.chave }, { it.rotulo }) { Tema.vidro = it }
    }

    private fun animacoes() {
        val c = cartao(str(R.string.aj_anim), str(R.string.aj_anim_dica))
        opcoes(c, "anim", Tema.Animacoes.values().toList(), Tema.animacoes, { it.chave }, { it.rotulo }) { Tema.animacoes = it }
    }
}
