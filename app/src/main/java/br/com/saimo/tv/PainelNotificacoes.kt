package br.com.saimo.tv

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * O sino do Início e o painel lateral da Central de Notificações.
 *
 * O painel abre por cima do Início (que continua visível ao fundo) e prende o foco: com ele aberto, o menu
 * lateral e o conteúdo do Início não recebem foco. VOLTAR ou "Fechar" fecham e o foco volta ao sino.
 * O número do sino conta só os avisos ainda não vistos.
 */
class PainelNotificacoes(
    private val tela: Activity,
    private val painel: View,
    private val sino: View,
    private val contador: TextView,
    private val bloqueados: List<ViewGroup>,
) {
    private val lista: RecyclerView = painel.findViewById(R.id.ntfLista)
    private val novas: TextView = painel.findViewById(R.id.ntfNovas)
    private val vazio: TextView = painel.findViewById(R.id.ntfVazio)
    private val fechar: View = painel.findViewById(R.id.ntfFechar)

    val aberto get() = painel.visibility == View.VISIBLE

    init {
        lista.layoutManager = LinearLayoutManager(tela)
        sino.setOnClickListener { abrir() }
        fechar.setOnClickListener { fechar() }
        fechar.nextFocusLeftId = fechar.id
        fechar.nextFocusRightId = fechar.id
        fechar.nextFocusDownId = fechar.id
        atualizarContador()
    }

    /** O número do sino: só os avisos não vistos. */
    fun atualizarContador() {
        val n = Notificacoes.naoLidas(tela)
        contador.text = if (n > 99) "99+" else n.toString()
        contador.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (aberto) cabecalho(n)
    }

    private fun cabecalho(n: Int) {
        novas.text = if (n == 1) tela.getString(R.string.ntf_nova) else tela.getString(R.string.ntf_novas, n)
        novas.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    fun abrir() {
        painel.visibility = View.VISIBLE
        for (g in bloqueados) g.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        montarLista()
        focarPrimeiro(8)
    }

    fun fechar() {
        painel.visibility = View.GONE
        for (g in bloqueados) g.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        sino.requestFocus()
    }

    /** Chegou aviso com o painel aberto: refaz a lista sem tirar a pessoa de onde está. */
    fun recarregar() {
        if (aberto) montarLista()
    }

    private fun montarLista() {
        val avisos = Notificacoes.ativos(tela)
        cabecalho(avisos.count { !it.lida })
        vazio.text = tela.getString(if (Trakt.conectado(tela)) R.string.ntf_vazio else R.string.ntf_vazio_trakt)
        vazio.visibility = if (avisos.isEmpty()) View.VISIBLE else View.GONE
        lista.visibility = if (avisos.isEmpty()) View.GONE else View.VISIBLE
        lista.adapter = NotificacoesLista(avisos, { ver(it) }, { abrirTitulo(it) }, fechar.id)
    }

    private fun ver(a: Notificacoes.Aviso) {
        Notificacoes.marcarLida(tela, a.id)
        atualizarContador()
    }

    /** O botão do aviso: abre a série ou o filme no Nuvio (pelo id do TMDB); sem o Nuvio, avisa. */
    private fun abrirTitulo(a: Notificacoes.Aviso) {
        Notificacoes.marcarLida(tela, a.id)
        when (Nuvio.abrirTitulo(tela, a.serie, null, a.tmdb)) {
            Nuvio.Resultado.ABRIU -> fechar()
            Nuvio.Resultado.SEM_APP -> Toast.makeText(tela, R.string.nuvio_sem_app, Toast.LENGTH_LONG).show()
            Nuvio.Resultado.FALHOU -> Toast.makeText(tela, R.string.nuvio_falha, Toast.LENGTH_LONG).show()
        }
        atualizarContador()
    }

    /** O foco vai ao aviso mais recente (ou a "Fechar", se não há nenhum); as capas desenham no quadro seguinte. */
    private fun focarPrimeiro(tentativas: Int) {
        if (!aberto) return
        if (lista.visibility != View.VISIBLE) {
            fechar.requestFocus()
            return
        }
        val botao = lista.findViewHolderForAdapterPosition(0)?.itemView?.findViewById<View>(R.id.ntfAcao)
        if (botao != null && botao.requestFocus()) return
        if (tentativas > 0) lista.postDelayed({ focarPrimeiro(tentativas - 1) }, 50) else fechar.requestFocus()
    }
}
