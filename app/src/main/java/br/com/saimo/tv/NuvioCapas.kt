package br.com.saimo.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** As capas de uma fileira da página Nuvio (Continuar assistindo e Minha Lista). */
class NuvioCapas(
    private val itens: List<NuvioDados.Titulo>,
    private val aoAbrir: (NuvioDados.Titulo) -> Unit,
    private val aoSairPelaEsquerda: () -> Unit,
) : RecyclerView.Adapter<NuvioCapas.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val moldura: View = view.findViewById(R.id.nuvioMoldura)
        val imagem: ImageView = view.findViewById(R.id.nuvioCapa)
        val inicial: TextView = view.findViewById(R.id.nuvioInicial)
        val progresso: ProgressBar = view.findViewById(R.id.nuvioProgresso)
        val nome: TextView = view.findViewById(R.id.nuvioNome)
        val detalhe: TextView = view.findViewById(R.id.nuvioDetalhe)
    }

    override fun onCreateViewHolder(pai: ViewGroup, tipo: Int) =
        Holder(LayoutInflater.from(pai.context).inflate(R.layout.item_nuvio_titulo, pai, false))

    override fun onBindViewHolder(holder: Holder, posicao: Int) {
        val titulo = itens[posicao]
        Cartoes.arredondar(holder.moldura)
        holder.inicial.text = titulo.nome.take(1).uppercase()
        Cartoes.carregar(holder.imagem, holder.inicial, titulo.capa)
        holder.nome.text = titulo.nome
        holder.detalhe.text = titulo.detalhe
        holder.detalhe.visibility = if (titulo.detalhe.isEmpty()) View.GONE else View.VISIBLE
        val fracao = titulo.progresso
        holder.progresso.visibility = if (fracao == null) View.GONE else View.VISIBLE
        if (fracao != null) holder.progresso.progress = (fracao * 1000).toInt()
        holder.itemView.setOnClickListener { aoAbrir(titulo) }
        holder.itemView.setOnKeyListener { _, codigo, evento ->
            if (codigo == KeyEvent.KEYCODE_DPAD_LEFT && evento.action == KeyEvent.ACTION_DOWN &&
                holder.bindingAdapterPosition == 0
            ) {
                aoSairPelaEsquerda()
                true
            } else {
                Cartoes.segurarNasPontas(holder, codigo, evento)
            }
        }
    }

    override fun getItemCount() = itens.size
}
