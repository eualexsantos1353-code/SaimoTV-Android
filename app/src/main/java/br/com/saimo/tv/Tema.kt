package br.com.saimo.tv

import android.app.Activity
import java.util.Calendar
import java.util.WeakHashMap

/**
 * Tema do ASTRAL: modo (Claro, Escuro, Automático), wallpaper (que traz o fundo
 * e a cor de destaque), Liquid Glass (opacidade das superfícies) e animações de
 * troca de tela. As telas apontam para atributos (?attr/astralAccent...) e este
 * objeto sobrepõe os valores certos ao tema de cada Activity ao criá-la.
 *
 * O gancho está em SaimoApp: nenhuma Activity precisa chamar nada.
 */
object Tema {

    /** Falso até as superfícies escuras fixas serem convertidas. Com ele falso,
     *  Claro e Automático ainda mostram o visual escuro. */
    const val CLARO_PRONTO = false

    enum class Modo(val chave: String) { CLARO("claro"), ESCURO("escuro"), AUTO("auto") }

    enum class Fundo(
        val chave: String, val rotulo: String,
        val estilo: Int, val vidroDesativado: Int, val vidroDiscreto: Int, val vidroIntenso: Int
    ) {
        AMBAR("ambar", "Âmbar/Laranja", R.style.AstralFundoAmbar,
            R.style.AstralVidroAmbarDesativado, R.style.AstralVidroAmbarDiscreto, R.style.AstralVidroAmbarIntenso),
        VERMELHO("vermelho", "Vermelho", R.style.AstralFundoVermelho,
            R.style.AstralVidroVermelhoDesativado, R.style.AstralVidroVermelhoDiscreto, R.style.AstralVidroVermelhoIntenso),
        AZUL("azul", "Azul", R.style.AstralFundoAzul,
            R.style.AstralVidroAzulDesativado, R.style.AstralVidroAzulDiscreto, R.style.AstralVidroAzulIntenso),
        ROXO("roxo", "Roxo", R.style.AstralFundoRoxo,
            R.style.AstralVidroRoxoDesativado, R.style.AstralVidroRoxoDiscreto, R.style.AstralVidroRoxoIntenso)
    }

    /** Liquid Glass: Discreto (levemente transparente), Intenso (bem transparente)
     *  e Desativado (superfícies sólidas). Sem desfoque pesado: a TV Box perderia fluidez. */
    enum class Vidro(val chave: String, val rotulo: String) {
        DISCRETO("discreto", "Discreto"),
        INTENSO("intenso", "Intenso"),
        DESATIVADO("desativado", "Desativado")
    }

    enum class Animacoes(val chave: String, val rotulo: String, val estilo: Int) {
        SUAVES("suaves", "Suaves", R.style.AstralAnimSuaves),
        DINAMICAS("dinamicas", "Dinâmicas", R.style.AstralAnimDinamicas),
        DESATIVADAS("desativadas", "Desativadas", R.style.AstralAnimDesativadas)
    }

    /** Telas de vídeo não são recriadas no meio da reprodução. */
    private val SEM_RECRIAR = setOf("PlayerActivity", "MainActivity")

    private val aplicadas = WeakHashMap<Activity, String>()

    var modo: Modo
        get() = Modo.values().firstOrNull { it.chave == Preferencias.temaModo } ?: Modo.ESCURO
        set(v) { Preferencias.temaModo = v.chave; mudou() }

    var fundo: Fundo
        get() = Fundo.values().firstOrNull { it.chave == Preferencias.temaFundo } ?: Fundo.ROXO
        set(v) { Preferencias.temaFundo = v.chave; mudou() }

    var vidro: Vidro
        get() = Vidro.values().firstOrNull { it.chave == Preferencias.temaVidro } ?: Vidro.DISCRETO
        set(v) { Preferencias.temaVidro = v.chave; mudou() }

    var animacoes: Animacoes
        get() = Animacoes.values().firstOrNull { it.chave == Preferencias.temaAnim } ?: Animacoes.SUAVES
        set(v) { Preferencias.temaAnim = v.chave; mudou() }

    /** Minutos desde a meia-noite em que o Claro começa (padrão 07:00). */
    var claroAs: Int
        get() = Preferencias.temaClaroAs
        set(v) { Preferencias.temaClaroAs = v.coerceIn(0, 1439); mudou() }

    /** Minutos desde a meia-noite em que o Escuro começa (padrão 19:00). */
    var escuroAs: Int
        get() = Preferencias.temaEscuroAs
        set(v) { Preferencias.temaEscuroAs = v.coerceIn(0, 1439); mudou() }

    fun claroAgora(): Boolean {
        if (!CLARO_PRONTO) return false
        return when (modo) {
            Modo.CLARO -> true
            Modo.ESCURO -> false
            Modo.AUTO -> {
                val c = Calendar.getInstance()
                val agora = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                val a = claroAs
                val b = escuroAs
                when {
                    a == b -> false
                    a < b -> agora in a until b
                    else -> agora >= a || agora < b
                }
            }
        }
    }

    private fun assinatura() =
        (if (claroAgora()) "c" else "e") + fundo.chave + vidro.chave + animacoes.chave

    /** Chamado por SaimoApp logo depois do onCreate da Activity, antes do layout. */
    fun aplicar(activity: Activity) {
        val t = activity.theme
        t.applyStyle(if (claroAgora()) R.style.AstralClaro else R.style.AstralEscuro, true)
        val f = fundo
        t.applyStyle(f.estilo, true)
        t.applyStyle(
            when (vidro) {
                Vidro.DISCRETO -> f.vidroDiscreto
                Vidro.INTENSO -> f.vidroIntenso
                Vidro.DESATIVADO -> f.vidroDesativado
            }, true)
        t.applyStyle(animacoes.estilo, true)
        aplicadas[activity] = assinatura()
    }

    /** Chamado quando a tela volta ao primeiro plano: o Automático pode ter virado. */
    fun verificar(activity: Activity) {
        if (activity.javaClass.simpleName in SEM_RECRIAR) return
        val aplicada = aplicadas[activity] ?: return
        if (aplicada != assinatura()) activity.recreate()
    }

    private fun mudou() {
        SaimoApp.atividadeAtual?.let { verificar(it) }
    }
}
