package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * TV ao Vivo (etapa 2): marca e busca no topo, categorias, lista de canais à esquerda,
 * prévia à direita e destaques esportivos embaixo.
 *
 * A prévia mostra a imagem do canal e o programa no ar (do guia que já existe) e, depois de
 * [ESPERA_MS] parado no canal, um mini player com o mesmo mecanismo de reprodução do player
 * de sempre ([Playback.mediaSource]). Um único player: é solto ao sair da tela, ao abrir o canal
 * e ao trocar de canal. Sem vídeo (carregando ou falha), a prévia segue com logo e programa.
 * O OK abre o player em tela cheia. Nada é inventado: sem guia, "Sem guia para este canal".
 */
@UnstableApi
class AoVivoActivity : TelaComMenu() {

    override val aba = Aba.AO_VIVO
    override val semMenu = true

    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, AoVivoActivity::class.java))
        private const val SO_O_GUIA = "so_o_guia"
        private const val TICK_MS = 20_000L
        /** Parado num canal por este tempo antes de a prévia tocar: descer a lista rápido não abre conexões. */
        private const val ESPERA_MS = 500L
        /** Falhas seguidas da prévia (queda, endereço vencido) antes de desistir; cada uma tenta a fonte seguinte. */
        private const val TENTATIVAS_MAX = 3
        private const val RECONECTAR_MS = 1_500L
    }

    private enum class Filtro(val rotulo: String) {
        TODOS("Todos"), ESPORTES("Esportes"), FILMES("Filmes e Séries"), FAVORITOS("Favoritos")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var todos: List<Channel> = emptyList()
    private var visiveis: List<Channel> = emptyList()
    private var filtro = Filtro.TODOS
    private var emFoco: Channel? = null
    private var iniciou = false

    private lateinit var lista: RecyclerView
    private lateinit var vazio: TextView
    private val adaptador = Adaptador()

    private val previaCanal by lazy { findViewById<TextView>(R.id.previaCanal) }
    private val previaTitulo by lazy { findViewById<TextView>(R.id.previaTitulo) }
    private val previaDescricao by lazy { findViewById<TextView>(R.id.previaDescricao) }
    private val previaDepois by lazy { findViewById<TextView>(R.id.previaDepois) }
    private val previaProgresso by lazy { findViewById<ProgressBar>(R.id.previaProgresso) }
    private val previaLogo by lazy { findViewById<ImageView>(R.id.previaLogo) }
    private val previaLogoGrande by lazy { findViewById<ImageView>(R.id.previaLogoGrande) }
    private val previaImagem by lazy { findViewById<ImageView>(R.id.previaImagem) }
    private val previaVideo by lazy { findViewById<PlayerView>(R.id.previaVideo) }
    private val previaEstado by lazy { findViewById<TextView>(R.id.previaEstado) }
    private var player: ExoPlayer? = null
    private var canalTocando: String? = null
    private var logoCarregado: String? = null
    private var imagemCarregada: String? = null

    private val iniciarVideo = Runnable { emFoco?.let { tocarPrevia(it) } }
    private var canalPrevia: Channel? = null
    private var tentativas = 0
    private val reconectar = Runnable {
        val p = player
        val c = canalPrevia
        if (p != null && c != null) prepararPrevia(p, c)
    }

    private val tick = object : Runnable {
        override fun run() {
            atualizarGuia()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sem canal nenhum a aba abre vazia, com o atalho para os endereços (igual ao player).
        if (Remote.channels.isEmpty() && Enderecos.canais != null) Remote.loadCached(this)
        if (MinhasListas.canaisDaTela().isEmpty()) {
            SemListaActivity.abrir(this, Aba.AO_VIVO)
            finish()
            return
        }
        iniciou = true
        setContentView(R.layout.activity_ao_vivo)
        Favorites.load(this)

        lista = findViewById(R.id.aoVivoLista)
        vazio = findViewById(R.id.aoVivoVazio)
        lista.layoutManager = LinearLayoutManager(this)
        lista.adapter = adaptador
        lista.setHasFixedSize(true)
        (lista.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

        findViewById<View>(R.id.aoVivoBusca).setOnClickListener {
            startActivity(Intent(this, BuscaActivity::class.java))
        }
        findViewById<View>(R.id.aoVivoPrevia).setOnClickListener { emFoco?.let { abrirCanal(it) } }

        montarCategorias()
        carregarCanais()
        montarDestaques()

        // O guia e os jogos chegam depois: a tela já está útil sem eles.
        lifecycleScope.launch(semDerrubar) { Epg.load(this@AoVivoActivity) { atualizarGuia() } }
        lifecycleScope.launch(semDerrubar) { if (JogosInicio.atualizar()) montarDestaques() }
        handler.postDelayed(tick, TICK_MS)

        focarQuandoPronto { lista.findViewHolderForAdapterPosition(0)?.itemView }
    }

    override fun onResume() {
        super.onResume()
        // Voltando do player (ou das configurações): favoritos e listas podem ter mudado.
        if (iniciou && ::lista.isInitialized && todos.isNotEmpty()) {
            Favorites.load(this)
            carregarCanais(manterFoco = true)
            emFoco?.let { agendarVideo(it) }
        }
    }

    override fun onPause() {
        // Solta o vídeo antes de qualquer outra tela tocar: o TV Box não aguenta dois players.
        pararVideo()
        super.onPause()
    }

    override fun onDestroy() {
        pararVideo()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // MARK: - Dados

    private fun carregarCanais(manterFoco: Boolean = false) {
        val ordenados = FontesDesativadas.peneirarCanais(
            Categorias.ordenar(MinhasListas.somar(Unlock.channels(), Unlock.unlocked)))
        todos = ordenados
        aplicarFiltro(manterFoco)
    }

    private fun filtrar(): List<Channel> = when (filtro) {
        Filtro.TODOS -> todos
        Filtro.ESPORTES -> todos.filter { Categorias.de(it) == "Esportes" }
        Filtro.FILMES -> todos.filter { Categorias.de(it) == "Filmes e Séries" }
        Filtro.FAVORITOS -> todos.filter { Favorites.contains(it.name) }
    }

    private fun aplicarFiltro(manterFoco: Boolean = false) {
        val nomeEmFoco = if (manterFoco) emFoco?.name else null
        visiveis = filtrar()
        adaptador.submeter(visiveis)
        vazio.visibility = if (visiveis.isEmpty()) View.VISIBLE else View.GONE
        if (visiveis.isEmpty()) {
            emFoco = null
            pararVideo()
            limparPrevia()
            return
        }
        val indice = nomeEmFoco?.let { n -> visiveis.indexOfFirst { it.name == n } }?.takeIf { it >= 0 } ?: 0
        mostrarPrevia(visiveis[indice])
        if (manterFoco && nomeEmFoco != null) {
            lista.scrollToPosition(indice)
            lista.post { lista.findViewHolderForAdapterPosition(indice)?.itemView?.requestFocus() }
        }
    }

    // MARK: - Categorias

    private fun montarCategorias() {
        val caixa = findViewById<LinearLayout>(R.id.aoVivoCategorias)
        caixa.removeAllViews()
        for (f in Filtro.values()) {
            val chip = layoutInflater.inflate(R.layout.item_ao_vivo_categoria, caixa, false) as TextView
            chip.text = f.rotulo
            chip.tag = f
            chip.isSelected = f == filtro
            chip.setOnClickListener {
                filtro = f
                for (i in 0 until caixa.childCount) caixa.getChildAt(i).isSelected = caixa.getChildAt(i).tag == f
                aplicarFiltro()
                focarQuandoPronto { lista.findViewHolderForAdapterPosition(0)?.itemView }
            }
            caixa.addView(chip)
        }
    }

    // MARK: - Prévia

    private fun limparPrevia() {
        previaCanal.text = ""
        previaTitulo.text = ""
        previaDescricao.text = ""
        previaDepois.text = ""
        previaProgresso.visibility = View.GONE
        previaLogo.dispose(); previaLogo.setImageDrawable(null); logoCarregado = null
        previaLogoGrande.dispose(); previaLogoGrande.setImageDrawable(null)
        previaImagem.dispose(); previaImagem.setImageDrawable(null); imagemCarregada = null
    }

    private fun mostrarPrevia(canal: Channel) {
        emFoco = canal
        val agora = System.currentTimeMillis()
        val par = Epg.nowNext(canal.name, agora)
        val noAr = par?.first
        val depois = par?.second

        previaCanal.text = canal.name
        previaTitulo.text = noAr?.title ?: canal.name
        previaDescricao.text = when {
            noAr == null -> "Sem guia para este canal"
            !noAr.description.isNullOrBlank() -> noAr.description
            else -> noAr.shortDetail.orEmpty()
        }
        previaDepois.text = depois?.let { "A seguir: ${it.title}" }.orEmpty()
        if (noAr != null) {
            previaProgresso.visibility = View.VISIBLE
            previaProgresso.progress = (noAr.progress(agora) * 1000).toInt()
        } else {
            previaProgresso.visibility = View.GONE
        }

        // Logo pequeno (na faixa) e grande (quando o guia não traz imagem do programa).
        if (logoCarregado != canal.logo) {
            logoCarregado = canal.logo
            if (canal.logo != null) {
                previaLogo.load(canal.logo)
                previaLogoGrande.load(canal.logo)
            } else {
                previaLogo.dispose(); previaLogo.setImageDrawable(null)
                previaLogoGrande.dispose(); previaLogoGrande.setImageDrawable(null)
            }
        }
        val poster = noAr?.poster?.takeIf { it.isNotBlank() }
        if (imagemCarregada != poster) {
            imagemCarregada = poster
            if (poster != null) previaImagem.load(poster)
            else { previaImagem.dispose(); previaImagem.setImageDrawable(null) }
        }
        atualizarLogoGrande()
    }

    /** O logo grande só aparece sem imagem do programa e sem vídeo por cima. */
    private fun atualizarLogoGrande() {
        previaLogoGrande.visibility =
            if (imagemCarregada == null && previaVideo.alpha < 1f) View.VISIBLE else View.GONE
    }

    // MARK: - Mini player

    private fun agendarVideo(canal: Channel) {
        handler.removeCallbacks(iniciarVideo)
        if (canalTocando == canal.name && player != null) return
        pararVideo()
        handler.postDelayed(iniciarVideo, ESPERA_MS)
    }

    private fun tocarPrevia(canal: Channel) {
        if (canal.sources.isEmpty()) return
        pararVideo()
        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(SaimoRenderersFactory(this))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 6_000, 400, 1_000)
                    .setTargetBufferBytes(8 * 1024 * 1024)
                    .build())
            .build()
        p.volume = 1f
        // Prévia pequena: o vídeo não precisa passar de 720p (480p no aparelho com pouca memória).
        val (largura, altura) = if (Aparelho.poucaMemoria) 854 to 480 else 1280 to 720
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(largura, altura)
            // Sem legenda na prévia: ela cobria o texto do programa.
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player !== p) return
                tentativas = 0
                previaVideo.alpha = 1f
                previaEstado.visibility = View.GONE
                atualizarLogoGrande()
            }

            override fun onPlaybackStateChanged(estado: Int) {
                if (player !== p) return
                if (estado == Player.STATE_BUFFERING && previaVideo.alpha < 1f) {
                    previaEstado.text = "Carregando prévia…"
                    previaEstado.visibility = View.VISIBLE
                }
            }

            override fun onPlayerError(erro: PlaybackException) {
                if (player !== p) return
                // A transmissão cai ou o endereço vence depois de um tempo: tenta de novo, na fonte
                // seguinte do canal. Só depois de TENTATIVAS_MAX falhas seguidas desiste.
                if (tentativas < TENTATIVAS_MAX) {
                    tentativas++
                    previaEstado.text = "Reconectando…"
                    previaEstado.visibility = View.VISIBLE
                    handler.removeCallbacks(reconectar)
                    handler.postDelayed(reconectar, RECONECTAR_MS)
                    return
                }
                liberarPlayer()
                previaEstado.text = "Prévia indisponível"
                previaEstado.visibility = View.VISIBLE
            }
        })
        player = p
        canalTocando = canal.name
        previaVideo.player = p
        previaVideo.useController = false
        previaVideo.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        canalPrevia = canal
        tentativas = 0
        prepararPrevia(p, canal)
    }

    /** Põe o canal no player: a fonte da vez é a de número [tentativas], voltando ao começo se faltar. */
    private fun prepararPrevia(p: ExoPlayer, canal: Channel) {
        val fonte = canal.sources[tentativas % canal.sources.size]
        try {
            p.setMediaSource(Playback.mediaSource(this, fonte))
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            liberarPlayer()
            previaEstado.text = "Prévia indisponível"
            previaEstado.visibility = View.VISIBLE
        }
    }

    private fun liberarPlayer() {
        val p = player ?: return
        player = null
        canalTocando = null
        canalPrevia = null
        handler.removeCallbacks(reconectar)
        previaVideo.player = null
        previaVideo.alpha = 0f
        atualizarLogoGrande()
        p.release()
    }

    /** Cancela a espera e solta o player; a prévia volta a mostrar logo e programa. */
    private fun pararVideo() {
        handler.removeCallbacks(iniciarVideo)
        liberarPlayer()
        if (::lista.isInitialized) previaEstado.visibility = View.GONE
    }

    private fun atualizarGuia() {
        if (!::lista.isInitialized) return
        adaptador.notifyItemRangeChanged(0, adaptador.itemCount, SO_O_GUIA)
        emFoco?.let { mostrarPrevia(it) }
    }

    // MARK: - Destaques

    private fun statusDe(j: JogosInicio.Jogo) = when (j.estado) {
        "in" -> "Ao vivo" + if (j.placar.isNotBlank()) " · ${j.placar}" else ""
        "post" -> "Encerrado" + if (j.placar.isNotBlank()) " · ${j.placar}" else ""
        else -> SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(j.inicio))
    }

    /**
     * Os jogos vêm da ESPN, a mesma fonte da fileira Jogos do Início. A ESPN não diz em que
     * canal o jogo passa, então tocar num cartão abre Eventos: nada aqui finge ter transmissão.
     */
    private fun montarDestaques() {
        val secao = findViewById<View>(R.id.aoVivoDestaquesSecao)
        val caixa = findViewById<LinearLayout>(R.id.aoVivoDestaques)
        val jogos = JogosInicio.jogos.take(3)
        caixa.removeAllViews()
        if (jogos.isEmpty()) { secao.visibility = View.GONE; return }
        secao.visibility = View.VISIBLE
        val abrirEventos = View.OnClickListener { startActivity(Intent(this, EventosActivity::class.java)) }
        for (j in jogos) {
            val cartao = layoutInflater.inflate(R.layout.item_ao_vivo_destaque, caixa, false)
            cartao.findViewById<TextView>(R.id.destaqueTitulo).text = j.titulo
            cartao.findViewById<TextView>(R.id.destaqueDetalhe).text = "${j.liga} · ${statusDe(j)}"
            val logo = cartao.findViewById<ImageView>(R.id.destaqueLogo)
            if (j.ligaLogo.isNotBlank()) logo.load(j.ligaLogo) else logo.setImageDrawable(null)
            cartao.setOnClickListener(abrirEventos)
            caixa.addView(cartao)
        }
        // "Ver Mais": o último cartão leva à tela de Eventos completa.
        val mais = layoutInflater.inflate(R.layout.item_ao_vivo_destaque, caixa, false)
        mais.findViewById<TextView>(R.id.destaqueTitulo).text = "Ver Mais"
        mais.findViewById<TextView>(R.id.destaqueDetalhe).text = "Todos os eventos"
        mais.findViewById<ImageView>(R.id.destaqueLogo).visibility = View.GONE
        mais.setOnClickListener(abrirEventos)
        (mais.layoutParams as LinearLayout.LayoutParams).apply { weight = 0.7f; marginEnd = 0 }
        caixa.addView(mais)
    }

    // MARK: - Ações

    private fun abrirCanal(canal: Channel) {
        pararVideo()
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, canal.name))
    }

    private fun favoritar(canal: Channel) {
        val era = Favorites.contains(canal.name)
        Favorites.toggle(this, canal.name)
        Toast.makeText(this,
            if (era) "${canal.name} saiu dos favoritos" else "${canal.name} nos favoritos",
            Toast.LENGTH_SHORT).show()
        carregarCanais(manterFoco = true)
    }

    // MARK: - Lista

    private class LinhaCanal(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.logo)
        val nome: TextView = view.findViewById(R.id.nome)
        val programa: TextView = view.findViewById(R.id.programa)
        val estrela: TextView = view.findViewById(R.id.estrela)
        var carregado: String? = null
    }

    private inner class Adaptador : RecyclerView.Adapter<LinhaCanal>() {
        private var itens: List<Channel> = emptyList()
        private val ids = HashMap<String, Long>()

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long =
            ids.getOrPut(itens[position].name) { ids.size.toLong() }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submeter(lista: List<Channel>) {
            itens = lista
            notifyDataSetChanged()
        }

        override fun getItemCount() = itens.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinhaCanal {
            val holder = LinhaCanal(
                LayoutInflater.from(parent.context).inflate(R.layout.item_ao_vivo_canal, parent, false))
            holder.itemView.setOnFocusChangeListener { _, tem ->
                if (tem) itens.getOrNull(holder.bindingAdapterPosition)?.let {
                    mostrarPrevia(it)
                    agendarVideo(it)
                }
            }
            holder.itemView.setOnClickListener {
                itens.getOrNull(holder.bindingAdapterPosition)?.let { abrirCanal(it) }
            }
            holder.itemView.setOnLongClickListener {
                itens.getOrNull(holder.bindingAdapterPosition)?.let { favoritar(it) }
                true
            }
            return holder
        }

        private fun mostrarNoAr(holder: LinhaCanal, canal: Channel) {
            val noAr = Epg.nowNext(canal.name, System.currentTimeMillis())?.first
            holder.programa.text = noAr?.title ?: "Sem guia"
        }

        override fun onBindViewHolder(holder: LinhaCanal, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it === SO_O_GUIA }) {
                mostrarNoAr(holder, itens[position])
            } else {
                onBindViewHolder(holder, position)
            }
        }

        override fun onBindViewHolder(holder: LinhaCanal, position: Int) {
            val canal = itens[position]
            holder.nome.text = canal.name
            holder.estrela.visibility = if (Favorites.contains(canal.name)) View.VISIBLE else View.GONE
            mostrarNoAr(holder, canal)
            if (holder.carregado != canal.logo) {
                holder.carregado = canal.logo
                if (canal.logo != null) holder.logo.load(canal.logo)
                else { holder.logo.dispose(); holder.logo.setImageDrawable(null) }
            }
        }
    }
}
