package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A tela inicial.
 *
 * Antes eram dois cartões — TV ou filmes — e só depois de escolher se via
 * alguma coisa. Agora é o que toda TV faz: um menu no topo, o título focado em
 * destaque com a imagem dele ao fundo, e fileiras — o que a pessoa estava
 * vendo, os canais ao vivo agora, os favoritos e as novidades. Tudo a um OK.
 *
 * Continua sendo a tela que o sistema abre (o nome da classe é o que o
 * launcher guarda), e é ela que atende os links `saimo://` da tela inicial da
 * TV e da busca por voz.
 */
@UnstableApi
class EscolhaActivity : TelaComMenu(), Connect.Listener {

    override val aba = Aba.INICIO
    override val semMenu = true
    override fun aoFocarMenu() = cabecalhoPadrao()
    override val aoMenuInicio: (() -> Unit) = { focarPrimeiro() }


    private lateinit var status: TextView
    private lateinit var filas: RecyclerView
    private lateinit var fundo: ImageView
    private lateinit var titulo: TextView
    private lateinit var meta: TextView
    private lateinit var sinopse: TextView
    private lateinit var painelHora: TextView
    private lateinit var painelData: TextView
    private lateinit var blocoContinuar: View
    private lateinit var banner: BannerInicio
    private lateinit var menuLateral: MenuLateral
    private lateinit var notificacoes: PainelNotificacoes
    private var tiques = 0
    private val dataDoPainel = SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR"))
    private val atualizacao by lazy { OfertaDeAtualizacao(this) { mostrarStatus(it) } }
    private var jaIniciou = false
    private val handler = Handler(Looper.getMainLooper())
    private val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    private var ultimoVoltar = 0L
    private var abertura: Abertura? = null
    private val depoisDaAbertura = mutableListOf<() -> Unit>()

    private val adaptador by lazy {
        Inicio.Adapter(escopo = lifecycleScope, capaDe = { t, s -> Generos.capa(t, s) },
            aoFocar = { mostrarDestaque(it) }, aoSairPelaEsquerda = { menuLateral.focar() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_escolha)
        // O Início tem fundo escuro neutro (as outras telas seguem com o fundo do app).
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF121116.toInt()))
        status = findViewById(R.id.escolhaStatus)
        filas = findViewById(R.id.inicioFilas)
        fundo = findViewById(R.id.inicioFundo)
        titulo = findViewById(R.id.inicioTitulo)
        meta = findViewById(R.id.inicioMeta)
        sinopse = findViewById(R.id.inicioSinopse)
        montarPainel()
        montarMenuLateral()

        filas.layoutManager = Inicio.Filas(this)
        filas.adapter = adaptador
        filas.setItemViewCacheSize(4)
        cabecalhoPadrao()

        // A abertura entra antes de tudo e a tela carrega por baixo dela.
        if (Abertura.deveMostrar(intent, savedInstanceState)) {
            // Fica guardada depois de acabar: a soltura da tecla que pulou
            // ainda passa por ela.
            abertura = Abertura(this) {
                depoisDaAbertura.toList().forEach { it() }
                depoisDaAbertura.clear()
            }.also { it.mostrar() }
        }

        lifecycleScope.launch(semDerrubar) {
            // Ler 990 canais do disco leva segundos num TV Box fraco: fora da
            // linha da tela, para ela aparecer já.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (Remote.channels.isEmpty()) Remote.loadCached(this@EscolhaActivity)
            }
            montarFilas()
            Epg.carregarCache(this@EscolhaActivity)
            montarFilas()
            // Primeira abertura (ou cache apagado): os endereços já estão ativos,
            // então baixa as listas sem a pessoa digitar nada.
            if (Remote.channels.isEmpty() && Enderecos.canais != null) {
                Listas.recarregar(this@EscolhaActivity.applicationContext)
                aoMudarListas()
            }
        }

        // A versão nova é oferecida aqui, antes de qualquer vídeo começar.
        filas.postDelayed({
            quandoAberturaAcabar { if (!isFinishing && Painel.dialogVisivel() == null) atualizacao.ofertar() }
        }, 2_500)
        lifecycleScope.launch(semDerrubar) { CanalNaTv.publicar(applicationContext) }

        val veioDeLink = intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEARCH
        atenderLink(intent)
        // "Ir direto ao último canal", escolhido em Ajustes: esta tela fica por
        // baixo, e VOLTAR do canal cai nela.
        if (savedInstanceState == null && !veioDeLink && Preferencias.abrirEm == "canal" &&
            Preferencias.ultimoCanal != null) {
            quandoAberturaAcabar { if (!isFinishing) startActivity(Intent(this, MainActivity::class.java)) }
        }
    }

    override fun aoMudarListas() {
        lifecycleScope.launch(semDerrubar) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Remote.loadCached(this@EscolhaActivity) }
            Epg.carregarCache(this@EscolhaActivity)
            montarFilas()
        }
    }

    /** Roda já, ou assim que a abertura sair de cena. */
    private fun quandoAberturaAcabar(acao: () -> Unit) {
        if (abertura?.ativa == true) depoisDaAbertura += acao else acao()
    }

    private fun cabecalhoPadrao() {
        painelDestaque.padrao(saudacao(), getString(R.string.escolha_titulo))
        sinopse.text = getString(if (Vr.ativo) R.string.vr_inicio_dica else R.string.inicio_dica)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        atenderLink(intent)
    }

    override fun onStart() {
        super.onStart()
        if (jaIniciou) {
            atualizacao.retomarSePendente()
            // Voltando do player ou da ficha: o "continuar" mudou. O guia pode
            // ter saído da memória com o app em segundo plano.
            lifecycleScope.launch(semDerrubar) {
                Epg.carregarCache(this@EscolhaActivity)
                montarFilas()
            }
        }
        jaIniciou = true
    }

    private var addonsVersaoVista = AddonsStore.versao.value

    override fun onResume() {
        super.onResume()
        Connect.listener = this
        Connect.notificarMudancaDeEstado()
        atualizarNotificacoes()
        if (::banner.isInitialized) banner.retomar()
        val atual = AddonsStore.versao.value
        if (atual != addonsVersaoVista) {
            addonsVersaoVista = atual
            lifecycleScope.launch(semDerrubar) { montarFilas() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (Connect.listener === this) Connect.listener = null
        if (::banner.isInitialized) banner.pausar()
    }

    override fun obterEstado(): org.json.JSONObject = org.json.JSONObject().apply {
        put("t", "state")
        put("mode", "idle")
        put("screen", "home")
        put("channel", "")
        put("playing", false)
        put("multi", false)
        put("active", 0)
        put("screens", org.json.JSONArray())
    }

    override fun sintonizarCanal(nome: String, metade: Int?) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, nome))
    }

    override fun simularTecla(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val focado = currentFocus
                if (focado == null) {
                    focarPainel()
                    return true
                }
                val proximo = focado.focusSearch(View.FOCUS_UP)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_UP)
                    return true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val focado = currentFocus
                if (focado == null) {
                    focarPrimeiro()
                    return true
                }
                val proximo = focado.focusSearch(View.FOCUS_DOWN)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_DOWN)
                    return true
                }
                filas.smoothScrollBy(0, 250)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val focado = currentFocus ?: return false
                val proximo = focado.focusSearch(View.FOCUS_LEFT)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_LEFT)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val focado = currentFocus ?: return false
                val proximo = focado.focusSearch(View.FOCUS_RIGHT)
                if (proximo != null && proximo !== focado) {
                    proximo.requestFocus(View.FOCUS_RIGHT)
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val focado = currentFocus
                if (focado != null) {
                    focado.performClick()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                val now = android.os.SystemClock.uptimeMillis()
                val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                onKeyDown(keyCode, event)
                return true
            }
        }
        val now = android.os.SystemClock.uptimeMillis()
        dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyEvent(KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }

    // MARK: - Painel

    /** O painel do topo: cada bloco abre a sua tela; focar um deles tira do fundo a imagem do item das fileiras. */
    private fun montarPainel() {
        painelHora = findViewById(R.id.inicioHora)
        painelData = findViewById(R.id.inicioData)
        blocoContinuar = findViewById(R.id.inicioBannerAssistir)
        val busca = findViewById<TextView>(R.id.inicioBusca)
        icone(busca, R.drawable.ic_search, 20, 0xFFB4B2A9.toInt())
        busca.setOnClickListener { BuscaActivity.abrir(this) }
        banner = BannerInicio(this, lifecycleScope, findViewById(R.id.inicioBanner)) {
            // O banner chegou: nos primeiros segundos, antes de a pessoa mexer, o foco sobe até ele.
            if (filas.hasFocus() && System.currentTimeMillis() - abertaEm < 8_000) focarPainel()
        }
        // O sino da Central de Notificações: depois da busca, antes do relógio. Não há nada acima nem à direita dele.
        val sino = findViewById<View>(R.id.inicioSino)
        sino.nextFocusUpId = sino.id
        sino.nextFocusRightId = sino.id
        notificacoes = PainelNotificacoes(this, findViewById(R.id.ntfPainel), sino, findViewById(R.id.inicioSinoContador),
            listOf(findViewById(R.id.inicioConteudo), findViewById(R.id.inicioMenu)))
        // O caminho até o sino: do botão do banner, para cima vai à busca e, no último título, para a direita vai ao sino.
        val busca2 = findViewById<View>(R.id.inicioBusca)
        busca2.nextFocusRightId = sino.id
        sino.nextFocusLeftId = busca2.id
        blocoContinuar.nextFocusUpId = busca2.id
        blocoContinuar.nextFocusRightId = sino.id
        val aoFocar = View.OnFocusChangeListener { _, foco -> if (foco) cabecalhoPadrao() }
        for (id in listOf(R.id.inicioBusca, R.id.inicioSino, R.id.inicioBannerAssistir)) {
            findViewById<View>(id).onFocusChangeListener = aoFocar
        }
        atualizarPainel()
        handler.post(tiquePainel)
        banner.carregar()
    }

    /**
     * O menu lateral: só ícones; com o foco abre e mostra os nomes. A seta para a esquerda chega nele
     * a partir da busca, do bloco grande e do começo de cada fileira; para a direita ele volta ao bloco grande.
     */
    private fun montarMenuLateral() {
        menuLateral = MenuLateral(this, findViewById(R.id.inicioMenu),
            MenuPrincipal.itens(this, Destino.INICIO) { focarPainel() }, blocoContinuar) { cabecalhoPadrao() }
        for (id in listOf(R.id.inicioBusca, R.id.inicioBannerAssistir)) findViewById<View>(id).nextFocusLeftId = menuLateral.primeiroId
    }

    /** A cada 30 s: a hora, a data e o que passa agora no último canal (o programa troca sozinho). */
    private val tiquePainel = object : Runnable {
        override fun run() {
            atualizarPainel()
            atualizarJogos()
            // A cada 10 minutos, olha de novo a Agenda atrás de lançamentos que chegaram com a TV ligada.
            if (++tiques % 20 == 0) atualizarNotificacoes()
            handler.postDelayed(this, 30_000)
        }
    }

    private fun atualizarPainel() {
        if (!::painelHora.isInitialized) return
        if (::notificacoes.isInitialized) notificacoes.atualizarContador()
        val agora = Date()
        painelHora.text = hora.format(agora)
        painelData.text = dataDoPainel.format(agora).replaceFirstChar { it.uppercase() }
    }

    /** Ícone à esquerda do texto (ou em cima dele), no tamanho e na cor pedidos. */
    private fun icone(texto: TextView, desenho: Int, dp: Int, cor: Int, emCima: Boolean = false) {
        val d = ContextCompat.getDrawable(this, desenho)?.mutate() ?: return
        val px = (dp * resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, px, px)
        d.setTint(cor)
        if (emCima) texto.setCompoundDrawablesRelative(null, d, null, null)
        else texto.setCompoundDrawablesRelative(d, null, null, null)
    }

    /** Cartão em linha: o ícone à esquerda do nome e a seta à direita. */
    private fun iconeLinha(texto: TextView, desenho: Int) {
        val px = (24 * resources.displayMetrics.density).toInt()
        fun tingido(id: Int) = ContextCompat.getDrawable(this, id)?.mutate()?.apply {
            setBounds(0, 0, px, px)
            setTint(0xFFFFFFFF.toInt())
        }
        texto.setCompoundDrawablesRelative(tingido(desenho), null, tingido(R.drawable.ic_inicio_seta), null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        abertura?.liberar()
        super.onDestroy()
    }

    private fun abrirAoVivo() {
        startActivity(Intent(this, AoVivoActivity::class.java))
    }

    private fun focarPrimeiro(tentativas: Int = 10) {
        filas.scrollToPosition(0)
        // O painel é o começo da tela: o foco vai ao bloco grande (Continuar ao vivo).
        if (::blocoContinuar.isInitialized && blocoContinuar.isShown && blocoContinuar.requestFocus()) return
        filas.post(Runnable { focarPrimeiraCapa(tentativas) })
    }

    private fun focarPrimeiraCapa(tentativas: Int) {
        val fileira = filas.findViewHolderForAdapterPosition(0)?.itemView
        val capas = fileira?.findViewById<RecyclerView>(R.id.filaCapas)
        if (capas?.getChildAt(0)?.requestFocus() == true) return
        // A fileira ainda não desenhou as capas: tenta de novo no próximo
        // quadro, em vez de largar o foco no menu.
        if (tentativas > 0) filas.postDelayed(Runnable { focarPrimeiro(tentativas - 1) }, 60)
        else if (vazioVisivel()) focarBotaoDoVazio()
        else focarPainel()
    }

    /** O começo da tela (sem a barra de abas): o bloco grande, com as fileiras roladas para o topo. */
    private fun focarPainel() {
        filas.scrollToPosition(0)
        if (::blocoContinuar.isInitialized && !blocoContinuar.requestFocus()) findViewById<View>(R.id.inicioBusca).requestFocus()
    }

    // MARK: - Fileiras

    private var primeiraVez = true
    private val abertaEm = System.currentTimeMillis()

    private suspend fun montarFilas() {
        // O que é local (continuar, ao vivo, favoritos) entra antes das fichas:
        // ler as fichas (3,7 MB) leva até 2 s num TV Box lento, e a tela
        // ficava vazia esse tempo todo. As capas dos destaques chegam depois.
        if (!Generos.prontos) {
            val locais = listOfNotNull(aoVivo(), jogos())
            if (locais.isNotEmpty()) mostrarFilas(locais)
            Generos.carregar(this)
        }
        val lista = mutableListOf<Inicio.Fila>()
        // Canais e Jogos abrem a tela; o "continuar" ficou só no bloco grande do painel.
        aoVivo()?.let { lista += it }
        jogos()?.let { lista += it }
        addonsVersaoVista = AddonsStore.versao.value
        mostrarFilas(lista)
        atualizarPainel()
    }

    private fun mostrarFilas(lista: List<Inicio.Fila>) {
        if (isFinishing || isDestroyed) return
        if (lista.isEmpty()) {
            val temFonte = Enderecos.temAlgum || MinhasListas.todas().isNotEmpty() || AddonsStore.temAtivo()
            mostrarVazio(getString(if (temFonte) R.string.vazio_falhou else R.string.vazio_inicio))
            cabecalhoPadrao()
            // Atrás da abertura animada o foco não chega; quando ela sai, vai ao botão.
            quandoAberturaAcabar { focarBotaoDoVazio() }
        } else esconderVazio()
        // Refazer as fileiras recria as capas e o foco escaparia para o menu.
        // Nos primeiros segundos (a pessoa ainda não mexeu), ele volta à
        // primeira capa; depois disso, fica onde a pessoa deixou.
        val devolver = primeiraVez || (filas.hasFocus() && System.currentTimeMillis() - abertaEm < 8_000)
        adaptador.trocar(lista)
        if (devolver) {
            primeiraVez = false
            filas.post { focarPrimeiro() }
        }
    }

    private fun continuar(): Inicio.Fila? {
        val itens = Progresso.emAndamento(this).take(20)
        if (itens.isEmpty()) return null
        return Inicio.Fila(getString(R.string.vod_continuar), itens.map { a ->
            val alvo = a.endereco?.alvo
            Inicio.Cartao(
                titulo = a.rotulo,
                capa = "",
                inicial = a.titulo.take(1).uppercase(),
                progresso = a.fracao,
                procurarCapa = a.serie,
                nomeDaCapa = a.titulo,
                alvo = alvo,
                aoMenu = {
                    Painel.mostrar(this, a.rotulo, listOfNotNull(
                        alvo?.let { Painel.Item(getString(R.string.inicio_abrir_ficha), icone = R.drawable.ic_info) { FichaActivity.abrir(this, it) } },
                        Painel.Item(getString(R.string.inicio_remover_continuar)) {
                            Progresso.esquecer(this, a)
                            lifecycleScope.launch(semDerrubar) { montarFilas() }
                        }))
                },
                aoRemover = {
                    Progresso.esquecer(this, a)
                    lifecycleScope.launch(semDerrubar) { montarFilas() }
                },
            ) {
                if (alvo != null) {
                    val (t, e) = if (a.terminado) a.temporada to a.episodio + 1 else a.temporada to a.episodio
                    PlayerActivity.abrir(this, alvo, t, e)
                } else {
                    BuscaActivity.abrir(this, Generos.semAno(a.titulo))
                }
            }
        })
    }

    /** Os canais favoritos — ou os abertos — com o que passa agora. */
    /**
     * A fileira Canais, com três abas: Abertos (TV aberta), Últimos (os que a pessoa viu por último) e
     * Favoritos. A aba escolhida fica salva e volta na próxima vez que o Início abre.
     */
    private fun aoVivo(): Inicio.Fila? {
        val todos = MinhasListas.somar(Remote.channels, false)
        if (todos.isEmpty()) return null
        CanaisRecentes.registrar(this, Preferencias.ultimoCanal)
        val guardado = getSharedPreferences("inicio", MODE_PRIVATE)
        val modo = guardado.getInt("modo_canais", 0).coerceIn(0, 2)

        fun cartoes(aba: Int): List<Inicio.Cartao> {
            val escolhidos = when (aba) {
                1 -> CanaisRecentes.lista(this).mapNotNull { nome -> todos.firstOrNull { it.name == nome } }
                2 -> todos.filter { Favorites.contains(it.name) }
                // TV aberta; e, se a lista não tem (as listas da própria pessoa), os primeiros canais dela.
                else -> todos.filter { Categorias.de(it) == "TV Aberta" }.ifEmpty { todos }
            }.distinct().take(20)
            return escolhidos.map { canal ->
                Inicio.Cartao(
                    titulo = canal.name,
                    capa = canal.logo.orEmpty(),
                    inicial = canal.name.take(1).uppercase(),
                    selo = getString(R.string.e1_ao_vivo),
                    seloAoVivo = true,
                ) {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CANAL, canal.name))
                }
            }
        }

        return Inicio.Fila(
            getString(R.string.e1_canais), cartoes(modo), Inicio.Tipo.LARGO,
            modos = listOf(
                getString(R.string.e1_canais_abertos),
                getString(R.string.e1_canais_ultimos),
                getString(R.string.e1_canais_favoritos),
            ),
            modoAtual = modo,
            aoModo = { escolhido ->
                guardado.edit().putInt("modo_canais", escolhido).apply()
                cartoes(escolhido)
            },
            aoVerTodos = { abrirAoVivo() },
        )
    }

    private val dataDoJogo = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

    /**
     * A fileira Jogos: ao vivo primeiro, depois os próximos e os que acabaram há pouco. Só placar e
     * horário (a ESPN não diz o canal), então tocar num jogo ainda não abre transmissão.
     */
    private fun jogos(): Inicio.Fila? {
        val lista = JogosInicio.jogos
        if (lista.isEmpty()) return null
        return Inicio.Fila(getString(R.string.e1_jogos), lista.map { j ->
            val vivo = j.estado == "in"
            val selo = when {
                vivo -> listOf(getString(R.string.e1_ao_vivo), j.placar).filter { it.isNotBlank() }.joinToString("  ")
                j.estado == "post" -> listOf(getString(R.string.e1_encerrado), j.placar).filter { it.isNotBlank() }.joinToString("  ")
                else -> dataDoJogo.format(Date(j.inicio))
            }
            Inicio.Cartao(
                titulo = j.titulo, capa = "", inicial = j.casa.take(1),
                subtitulo = j.liga,
                colagem = listOf(j.casaLogo, j.foraLogo, j.ligaLogo),
                selo = selo, seloAoVivo = vivo,
            ) {
                Toast.makeText(this, R.string.e1_jogo_sem_canal, Toast.LENGTH_SHORT).show()
            }
        }, Inicio.Tipo.EVENTO)
    }

    private fun atualizarJogos() {
        lifecycleScope.launch(semDerrubar) { if (JogosInicio.atualizar()) montarFilas() }
    }

    private fun favoritos(): Inicio.Fila? {
        val itens = VodFavoritos.lista(this)
        if (itens.isEmpty()) return null
        return Inicio.Fila(getString(R.string.vod_favoritos), itens.map { item ->
            val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano)
            Inicio.Cartao(
                titulo = item.nomeCompleto, capa = "", inicial = item.titulo.take(1).uppercase(),
                procurarCapa = item.serie, alvo = alvo,
                aoMenu = { alternarFavorito(alvo) },
            ) { FichaActivity.abrir(this, alvo) }
        })
    }

    private fun cartaoDe(item: Destaques.Item): Inicio.Cartao {
        val alvo = Alvo(item.titulo, item.serie, item.letra, item.ano,
            colecao = if (item.daColecao) item.colecao else "")
        return Inicio.Cartao(
            titulo = item.titulo, capa = item.capa, inicial = item.titulo.take(1).uppercase(), trailer = item.trailer,
            progresso = if (item.serie) null else Progresso.fracao(this, Progresso.chaveFilme(item.titulo)),
            alvo = alvo,
            aoMenu = if (item.daColecao) null else ({ alternarFavorito(alvo) }),
        ) { FichaActivity.abrir(this, alvo) }
    }

    private fun alternarFavorito(alvo: Alvo) {
        val marcado = VodFavoritos.alternar(this, VodFavoritos.Item(alvo.titulo, alvo.serie, alvo.letra, alvo.ano))
        Toast.makeText(this, if (marcado) R.string.vod_ficha_favorito else R.string.inicio_desfavoritar,
            Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(semDerrubar) { montarFilas() }
    }

    // MARK: - Destaque

    private val painelDestaque by lazy { Destaque(this, lifecycleScope, fundo, titulo, meta, sinopse) { it.aoEscolher() } }

    /** O banner do topo cuida da imagem grande: focar um canal ou jogo não troca mais o fundo da tela. */
    @Suppress("UNUSED_PARAMETER")
    private fun mostrarDestaque(cartao: Inicio.Cartao) {}

    // MARK: - Teclas

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Durante a abertura qualquer tecla pula, e nenhuma chega às fileiras.
        if (abertura?.tecla(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    /** Cria os avisos novos da Agenda (a conta do Trakt) e atualiza o sino. */
    private fun atualizarNotificacoes() {
        lifecycleScope.launch(semDerrubar) {
            val novos = Notificacoes.atualizar(applicationContext)
            if (isDestroyed || !::notificacoes.isInitialized) return@launch
            notificacoes.atualizarContador()
            if (novos > 0) notificacoes.recarregar()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Com o painel de notificações aberto, VOLTAR só o fecha (e o foco volta ao sino).
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) &&
            ::notificacoes.isInitialized && notificacoes.aberto) {
            notificacoes.fechar()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_TV -> { abrirAoVivo(); return true }
            KeyEvent.KEYCODE_SEARCH -> { BuscaActivity.abrir(this); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                // Primeiro VOLTAR sobe para o bloco grande; o segundo, de lá, pede
                // confirmação — sair do app sem querer é o erro mais comum.
                if (!blocoContinuar.hasFocus() && blocoContinuar.isShown) {
                    focarPainel()
                    return true
                }
                val agora = System.currentTimeMillis()
                if (agora - ultimoVoltar > 2_500) {
                    ultimoVoltar = agora
                    Toast.makeText(this, R.string.inicio_sair, Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Link vindo de fora do app: o "Continuar assistindo" e a fileira do
     * Saimo na tela inicial, e a busca por voz do sistema.
     */
    private fun atenderLink(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                if (intent.data?.host == "inicio") return
                Links.abrir(this, intent.data)
                intent.data = null
            }
            Intent.ACTION_SEARCH -> {
                val termo = intent.getStringExtra(android.app.SearchManager.QUERY)?.trim().orEmpty()
                if (termo.isNotEmpty()) BuscaActivity.abrir(this, termo)
                intent.action = null
            }
        }
    }

    /** "Boa noite" às dez da noite: a abertura fala como gente. */
    private fun saudacao(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return getString(when (h) {
            in 5..11 -> R.string.escolha_bom_dia
            in 12..17 -> R.string.escolha_boa_tarde
            else -> R.string.escolha_boa_noite
        })
    }

    private fun mostrarStatus(texto: String) {
        status.text = texto
        status.visibility = View.VISIBLE
    }

    companion object {
        /** Volta à tela inicial, fechando o que estiver por cima dela. */
        fun voltar(context: Context) = context.startActivity(Intent(context, EscolhaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
