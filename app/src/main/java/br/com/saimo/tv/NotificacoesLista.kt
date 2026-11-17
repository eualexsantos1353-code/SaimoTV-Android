package br.com.saimo.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** A lista de avisos do painel da Central. Ver um aviso (o foco no botão dele) o marca como visto. */
class NotificacoesLista(
    private val avisos: List<Notificacoes.Aviso>,
    private val aoVer: (Notificacoes.Aviso) -> Unit,
    private val aoAbrir: (Notificacoes.Aviso) -> Unit,
    private val idDoFechar: Int,
) : RecyclerView.Adapter<NotificacoesLista.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val moldura: View = view.findViewById(R.id.ntfMoldura)
        val capa: ImageView = view.findViewById(R.id.ntfCapa)
        val inicial: TextView = view.findViewById(R.id.ntfInicial)
        val ponto: View = view.findViewById(R.id.ntfPonto)
        val tipo: TextView = view.findViewById(R.id.ntfTipo)
        val titulo: TextView = view.findViewById(R.id.ntfTitulo)
        val detalhe: TextView = view.findViewById(R.id.ntfDetalhe)
        val acao: TextView = view.findViewById(R.id.ntfAcao)
        val tempo: TextView = view.findViewById(R.id.ntfTempo)
    }

    override fun onCreateViewHolder(pai: ViewGroup, tipo: Int) =
        Holder(LayoutInflater.from(pai.context).inflate(R.layout.item_notificacao, pai, false)).also {
            it.acao.id = View.generateViewId()
        }

    override fun onBindViewHolder(h: Holder, posicao: Int) {
        val a = avisos[posicao]
        val c = h.itemView.context
        Cartoes.arredondar(h.moldura)
        h.inicial.text = a.titulo.take(1).uppercase()
        Cartoes.carregar(h.capa, h.inicial, a.poster?.let { "https://image.tmdb.org/t/p/w185$it" })
        h.ponto.visibility = if (a.lida) View.INVISIBLE else View.VISIBLE
        h.tipo.text = c.getString(if (a.serie) R.string.ntf_tipo_episodio else R.string.ntf_tipo_filme)
        h.titulo.text = a.titulo
        h.detalhe.text = if (a.serie) c.getString(R.string.ntf_detalhe_episodio, a.temporada, a.numero)
            else c.getString(R.string.ntf_detalhe_filme)
        h.acao.text = c.getString(if (a.serie) R.string.ntf_abrir_episodio else R.string.ntf_abrir_filme)
        h.tempo.text = Notificacoes.tempo(c, a.recebida)
        // O foco fica preso ao painel: para os lados fica onde está; em cima do primeiro, também; embaixo do último, vai a "Fechar".
        h.acao.nextFocusLeftId = h.acao.id
        h.acao.nextFocusRightId = h.acao.id
        if (posicao == 0) h.acao.nextFocusUpId = h.acao.id
        h.acao.nextFocusDownId = if (posicao == avisos.size - 1) idDoFechar else View.NO_ID
        h.acao.setOnFocusChangeListener { _, foco ->
            if (foco && !a.lida) {
                a.lida = true
                h.ponto.visibility = View.INVISIBLE
                aoVer(a)
            }
        }
        h.acao.setOnClickListener { aoAbrir(a) }
    }

    override fun getItemCount() = avisos.size
}
