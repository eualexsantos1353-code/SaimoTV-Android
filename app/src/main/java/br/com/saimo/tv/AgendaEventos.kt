package br.com.saimo.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A lista do painel do dia da Agenda. OK abre o título no Nuvio; a seta para a esquerda volta ao calendário. */
class AgendaEventos(
    private val itens: List<AgendaDados.Item>,
    private val aoAbrir: (AgendaDados.Item) -> Unit,
    private val aoSairPelaEsquerda: () -> Unit,
) : RecyclerView.Adapter<AgendaEventos.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val moldura: View = view.findViewById(R.id.agendaMoldura)
        val imagem: ImageView = view.findViewById(R.id.agendaCapa)
        val inicial: TextView = view.findViewById(R.id.agendaInicial)
        val nome: TextView = view.findViewById(R.id.agendaNome)
        val detalhe: TextView = view.findViewById(R.id.agendaDetalhe)
        val meta: TextView = view.findViewById(R.id.agendaMeta)
        val contagem: TextView = view.findViewById(R.id.agendaContagem)
    }

    override fun onCreateViewHolder(pai: ViewGroup, tipo: Int) =
        Holder(LayoutInflater.from(pai.context).inflate(R.layout.item_agenda_evento, pai, false))

    override fun onBindViewHolder(holder: Holder, posicao: Int) {
        val item = itens[posicao]
        val c = holder.itemView.context
        Cartoes.arredondar(holder.moldura)
        holder.inicial.text = item.titulo.take(1).uppercase()
        Cartoes.carregar(holder.imagem, holder.inicial, item.poster?.let { "https://image.tmdb.org/t/p/w185$it" })
        holder.nome.text = item.titulo
        holder.detalhe.text = when (item.tipo) {
            AgendaDados.EPISODIO -> c.getString(R.string.agenda_ep, item.temporada, item.numero)
            AgendaDados.ESTREIA -> c.getString(R.string.agenda_estreia_serie)
            AgendaDados.TEMPORADA -> c.getString(R.string.agenda_nova_temporada, item.temporada)
            else -> c.getString(when (item.lancamento) {
                3 -> R.string.agenda_filme_cinema
                4 -> R.string.agenda_filme_digital
                2 -> R.string.agenda_filme_limitada
                else -> R.string.agenda_filme
            })
        }
        val hora = if (item.comHora) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.em)) else ""
        val meta = listOf(hora, item.emissora).filter { it.isNotEmpty() }.joinToString(" · ")
        holder.meta.text = meta
        holder.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        val dias = AgendaDados.diasEntre(System.currentTimeMillis(), item.em)
        holder.contagem.text = when {
            dias == 0 -> c.getString(R.string.agenda_hoje)
            dias == 1 -> c.getString(R.string.agenda_amanha)
            dias == -1 -> c.getString(R.string.agenda_ontem)
            dias > 1 -> c.getString(R.string.agenda_em_dias, dias)
            else -> c.getString(R.string.agenda_ha_dias, -dias)
        }
        holder.itemView.setOnClickListener { aoAbrir(item) }
        holder.itemView.setOnKeyListener { _, codigo, evento ->
            if (evento.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                codigo == KeyEvent.KEYCODE_DPAD_LEFT -> { aoSairPelaEsquerda(); true }
                codigo == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                codigo == KeyEvent.KEYCODE_DPAD_DOWN && holder.bindingAdapterPosition == itemCount - 1 -> true
                else -> false
            }
        }
    }

    override fun getItemCount() = itens.size
}
