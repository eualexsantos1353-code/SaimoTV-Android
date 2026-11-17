package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.content.ActivityNotFoundException
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Configurações (etapa 5): o menu das categorias à esquerda e o conteúdo da categoria aberta à direita.
 *
 * Personalização reúne Aparência (a tela nova, ver [AparenciaPainel]) e Tela inicial (ainda por definir).
 * Controle e Acessibilidade e Sobre o ASTRAL aguardam os documentos próprios. "Mais ajustes" guarda, sem
 * mudar nada, a lista que já existia (reprodução, listas, conexão, sistema) até esses documentos chegarem.
 *
 * Mudar o wallpaper, o Liquid Glass ou as animações recria a tela: o estado (categoria, subpágina e item
 * com foco) vai no onSaveInstanceState e volta no onCreate.
 */
@UnstableApi
class AjustesActivity : TelaComMenu() {

    override val aba = Aba.AJUSTES
    override val semMenu = true


    companion object {
        fun abrir(context: Context) = context.startActivity(Intent(context, AjustesActivity::class.java))
    }

    private sealed interface Linha
    private data class Secao(val nome: String) : Linha
    private data class Opcao(val nome: String, val valor: () -> String, val acao: () -> Unit) : Linha

    private lateinit var lista: RecyclerView
    private val adaptador = Adaptador()
    private val atualizacao by lazy { OfertaDeAtualizacao(this) { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }

    /// Minhas listas: lista a atualizar pelo arquivo (nulo = lista nova).
    private var alvoArquivo: String? = null
    private val seletorDeArquivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        MinhasListasTela.aoEscolherArquivo(this, uri, alvoArquivo, ::pedirArquivo) { redesenhar() }
    }

    private val permissaoDePastas = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MinhasListasTela.mostrarArquivos(this, alvoArquivo, ::pedirArquivo) { redesenhar() }
        else Toast.makeText(this, "Sem permissão para ver os arquivos. Use o link da lista.", Toast.LENGTH_LONG).show()
    }

    /**
     * Seletor de arquivos do sistema (SAF). Muitas TVs trazem só um "stub" no
     * lugar (a pessoa veria um aviso em inglês) ou nenhum app: aí a escolha é
     * feita numa lista própria dos .m3u de Download/Documents.
     */
    private fun pedirArquivo(alvo: String?) {
        alvoArquivo = alvo
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val dono = intent.resolveActivity(packageManager)?.packageName
        if (dono == null || dono == "com.android.tv.frameworkpackagestubs") { listarPastas(); return }
        try {
            seletorDeArquivo.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            listarPastas()
        }
    }

    private fun listarPastas() {
        val precisa = android.os.Build.VERSION.SDK_INT in 23..32 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (precisa) permissaoDePastas.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else MinhasListasTela.mostrarArquivos(this, alvoArquivo, ::pedirArquivo) { redesenhar() }
    }

    private enum class Cat { NENHUMA, PERS, CONTROLE, MAIS, SOBRE }

    private var categoria = Cat.NENHUMA
    private var subpagina = ""
    private var chaveFoco: String? = null
    private var primeiroFoco = true

    private lateinit var linhasMenu: Map<Cat, View>
    private lateinit var tituloPg: TextView
    private lateinit var descricaoPg: TextView
    private lateinit var caminhoPg: TextView
    private lateinit var textoCorpo: TextView
    private lateinit var textoNota: TextView
    private lateinit var pgTexto: View
    private lateinit var pgPers: View
    private lateinit var pgAparencia: View
    private lateinit var aparencia: AparenciaPainel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes)
        tituloPg = findViewById(R.id.ajTitulo)
        descricaoPg = findViewById(R.id.ajDescricao)
        caminhoPg = findViewById(R.id.ajCaminho)
        textoCorpo = findViewById(R.id.ajTextoCorpo)
        textoNota = findViewById(R.id.ajTextoNota)
        pgTexto = findViewById(R.id.ajPgTexto)
        pgPers = findViewById(R.id.ajPgPers)
        pgAparencia = findViewById(R.id.ajPgAparencia)
        lista = findViewById(R.id.ajustesLista)
        lista.layoutManager = LinearLayoutManager(this)
        lista.adapter = adaptador
        adaptador.linhas = linhas()

        aparencia = AparenciaPainel(this, findViewById(R.id.ajAparenciaConteudo), R.id.ajCatPers, ::escolherHora) { chaveFoco = it }

        linhasMenu = mapOf(
            Cat.PERS to findViewById<View>(R.id.ajCatPers),
            Cat.CONTROLE to findViewById<View>(R.id.ajCatControle),
            Cat.MAIS to findViewById<View>(R.id.ajCatMais),
            Cat.SOBRE to findViewById<View>(R.id.ajCatSobre),
        )
        for ((c, v) in linhasMenu) {
            v.setOnClickListener { abrirCategoria(c) }
            // Seta para a direita: abre a categoria e leva o foco para dentro dela.
            v.setOnKeyListener { _, tecla, evento ->
                if (tecla == KeyEvent.KEYCODE_DPAD_RIGHT && evento.action == KeyEvent.ACTION_DOWN) { abrirCategoria(c); true } else false
            }
            // Andar pelo menu mostra a categoria; o primeiro foco, ao abrir a tela, deixa a tela de boas-vindas.
            v.setOnFocusChangeListener { _, tem ->
                if (tem) {
                    val primeira = primeiroFoco
                    primeiroFoco = false
                    if (!primeira) mostrarPagina(c, "")
                }
            }
        }
        findViewById<View>(R.id.ajRowAparencia).apply {
            nextFocusLeftId = R.id.ajCatPers
            setOnClickListener {
                mostrarPagina(Cat.PERS, "aparencia")
                aparencia.focarPrimeiro()
            }
        }
        findViewById<View>(R.id.ajRowTelaInicial).apply {
            nextFocusLeftId = R.id.ajCatPers
            setOnClickListener {
                mostrarPagina(Cat.PERS, "telainicial")
                linhasMenu[Cat.PERS]?.requestFocus()
            }
        }

        val salva = savedInstanceState?.getString("cat")?.let { n -> Cat.values().firstOrNull { it.name == n } }
        if (salva != null && salva != Cat.NENHUMA) {
            primeiroFoco = false
            mostrarPagina(salva, savedInstanceState?.getString("sub") ?: "")
            val chave = savedInstanceState?.getString("chave")
            focarQuandoPronto {
                chave?.let { pgAparencia.findViewWithTag<View>(it) } ?: linhasMenu.getValue(salva)
            }
        } else {
            mostrarPagina(Cat.NENHUMA, "")
            focarQuandoPronto { linhasMenu.getValue(Cat.PERS) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cat", categoria.name)
        outState.putString("sub", subpagina)
        outState.putString("chave", chaveFoco ?: (currentFocus?.tag as? String))
    }

    private fun cabecalhoPg(t: Int, d: Int?) {
        tituloPg.setText(t)
        if (d != null) { descricaoPg.setText(d); descricaoPg.visibility = View.VISIBLE } else descricaoPg.visibility = View.GONE
    }

    private fun corpoTexto(c: CharSequence, nota: Int?) {
        textoCorpo.text = c
        textoCorpo.visibility = if (c.isEmpty()) View.GONE else View.VISIBLE
        if (nota != null) { textoNota.setText(nota); textoNota.visibility = View.VISIBLE } else textoNota.visibility = View.GONE
        pgTexto.visibility = View.VISIBLE
    }

    /** Mostra a categoria (e a subpágina) no painel da direita, sem tirar o foco de onde está. */
    private fun mostrarPagina(c: Cat, s: String) {
        categoria = c
        subpagina = s
        for ((k, v) in linhasMenu) v.isSelected = k == c
        pgTexto.visibility = View.GONE
        pgPers.visibility = View.GONE
        pgAparencia.visibility = View.GONE
        lista.visibility = View.GONE
        caminhoPg.visibility = View.GONE
        when {
            c == Cat.NENHUMA -> { cabecalhoPg(R.string.aj_configuracoes, R.string.aj_config_desc); corpoTexto("", R.string.aj_config_dica) }
            c == Cat.PERS && s == "aparencia" -> {
                cabecalhoPg(R.string.aj_aparencia, R.string.aj_aparencia_desc)
                caminhoPg.visibility = View.VISIBLE
                aparencia.montar()
                pgAparencia.visibility = View.VISIBLE
            }
            c == Cat.PERS && s == "telainicial" -> {
                cabecalhoPg(R.string.aj_tela_inicial, R.string.aj_tela_inicial_linha)
                corpoTexto(getString(R.string.aj_tela_inicial_nota), null)
            }
            c == Cat.PERS -> { cabecalhoPg(R.string.aj_pers, R.string.aj_pers_desc); pgPers.visibility = View.VISIBLE }
            c == Cat.CONTROLE -> { cabecalhoPg(R.string.aj_controle, null); corpoTexto(getString(R.string.aj_controle_nota), null) }
            c == Cat.MAIS -> { cabecalhoPg(R.string.aj_mais, R.string.aj_mais_desc); lista.visibility = View.VISIBLE }
            else -> {
                cabecalhoPg(R.string.aj_sobre, null)
                corpoTexto(getString(R.string.ajustes_sobre, BuildConfig.VERSION_NAME, Preferencias.idCurto(this)), R.string.aj_sobre_nota)
            }
        }
    }

    /** OK ou seta para a direita numa categoria: abre e leva o foco ao primeiro item dela (se houver). */
    private fun abrirCategoria(c: Cat) {
        mostrarPagina(c, "")
        when (c) {
            Cat.PERS -> findViewById<View>(R.id.ajRowAparencia).requestFocus()
            Cat.MAIS -> lista.postDelayed({ lista.findViewHolderForAdapterPosition(1)?.itemView?.requestFocus() }, 80)
            else -> {}
        }
    }

    private fun escolherHora(tituloPg: String, atual: Int, aoEscolher: (Int) -> Unit) {
        escolher(tituloPg, (0 until 48).map { it * 30 }, atual, { "%02d:%02d".format(it / 60, it % 60) }, aoEscolher)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            // Numa subpágina, VOLTAR leva à lista da categoria; dentro do painel, ao menu; no menu, sai.
            if (subpagina.isNotEmpty()) {
                val linha = if (subpagina == "aparencia") R.id.ajRowAparencia else R.id.ajRowTelaInicial
                mostrarPagina(Cat.PERS, "")
                findViewById<View>(linha).requestFocus()
                return true
            }
            val foco = currentFocus
            if (categoria != Cat.NENHUMA && foco != null && !linhasMenu.containsValue(foco)) {
                linhasMenu[categoria]?.requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (::lista.isInitialized) redesenhar()
    }

    private fun simNao(v: Boolean) = getString(if (v) R.string.ajustes_ligado else R.string.ajustes_desligado)

    private fun rotuloUpscaling(modo: String) = getString(
        when (modo) {
            Upscaling.NITIDEZ_HD -> R.string.ajustes_upscaling_hd
            Upscaling.SUPER_4K -> R.string.ajustes_upscaling_4k
            else -> R.string.ajustes_upscaling_off
        }
    )

    private fun rotuloZeroDelay(modo: String) = when (modo) {
        "equilibrado" -> "Equilibrado (Recomendado)"
        "agressivo" -> "Agressivo (Ultra Ao Vivo)"
        else -> "Desligado"
    }

    private fun linhas(): List<Linha> = listOf(
        Secao(getString(R.string.ajustes_reproducao)),
        Opcao(getString(R.string.ajustes_versao), { Titulos.rotulo(Preferencias.versao) }) {
            escolher(getString(R.string.ajustes_versao), listOf("dub", "leg"), Preferencias.versao,
                { Titulos.rotulo(it) }) { Preferencias.versao = it }
        },
        Opcao(getString(R.string.ajustes_proximo), { simNao(Preferencias.proximoAutomatico) }) {
            Preferencias.proximoAutomatico = !Preferencias.proximoAutomatico; redesenhar()
        },
        Opcao(getString(R.string.ajustes_pular), { simNao(Preferencias.pularAutomatico) }) {
            Preferencias.pularAutomatico = !Preferencias.pularAutomatico; redesenhar()
        },
        Opcao(getString(R.string.player_tamanho_legenda),
            { resources.getStringArray(R.array.tamanhos_legenda)[Preferencias.legenda.coerceIn(0, 3)] }) {
            val nomes = resources.getStringArray(R.array.tamanhos_legenda)
            escolher(getString(R.string.player_tamanho_legenda), nomes.indices.toList(), Preferencias.legenda,
                { nomes[it] }) { Preferencias.legenda = it }
        },
        Opcao(getString(R.string.ajustes_legenda_fundo), { simNao(Preferencias.legendaFundo) }) {
            Preferencias.legendaFundo = !Preferencias.legendaFundo; redesenhar()
        },
        Opcao(getString(R.string.ajustes_economia), { simNao(Preferencias.economia) }) {
            Preferencias.economia = !Preferencias.economia; redesenhar()
        },
        Opcao(getString(R.string.ajustes_audio_inteligente), { simNao(Preferencias.audioInteligente) }) {
            Preferencias.audioInteligente = !Preferencias.audioInteligente
            AudioInteligente.atualizarTodos()
            redesenhar()
        },
        Opcao("Zero Delay (Menor Atraso)", { rotuloZeroDelay(Preferencias.zeroDelay) }) {
            escolher(
                "Zero Delay (Menor Atraso)",
                listOf("off", "equilibrado", "agressivo"),
                Preferencias.zeroDelay,
                { rotuloZeroDelay(it) }
            ) { Preferencias.zeroDelay = it }
        },
        Opcao(getString(R.string.ajustes_upscaling), { rotuloUpscaling(Preferencias.upscaling) }) {
            escolher(
                getString(R.string.ajustes_upscaling),
                Upscaling.modos,
                Preferencias.upscaling,
                { rotuloUpscaling(it) }
            ) { Preferencias.upscaling = it }
        },

        Secao(getString(R.string.ajustes_geral)),
        Opcao(getString(R.string.ajustes_listas), { Listas.resumo(this) }) {
            ListasDialogo.mostrar(this) { redesenhar() }
        },
        Opcao(getString(R.string.ajustes_minhas_listas), { MinhasListas.resumo() }) {
            MinhasListasTela.mostrar(this, ::pedirArquivo) { redesenhar() }
        },
        Opcao(getString(R.string.ajustes_addons), { AddonsStore.resumo() }) {
            AddonsTela.abrir(this)
        },
        Opcao(getString(R.string.ajustes_abrir_em), {
            getString(if (Preferencias.abrirEm == "canal") R.string.ajustes_abrir_canal else R.string.ajustes_abrir_inicio)
        }) {
            escolher(getString(R.string.ajustes_abrir_em), listOf("inicio", "canal"), Preferencias.abrirEm,
                { getString(if (it == "canal") R.string.ajustes_abrir_canal else R.string.ajustes_abrir_inicio) }) {
                Preferencias.abrirEm = it
            }
        },
        Opcao(getString(R.string.ajustes_timer), {
            if (TimerDeSono.ativo) getString(R.string.ajustes_timer_em, TimerDeSono.restanteMin())
            else getString(R.string.ajustes_desligado)
        }) {
            escolher(getString(R.string.ajustes_timer), listOf(0, 30, 60, 90, 120, 180), -1,
                { if (it == 0) getString(R.string.ajustes_desligado) else getString(R.string.ajustes_minutos, it) }) {
                TimerDeSono.ligar(it)
            }
        },

        Opcao(getString(R.string.ajustes_connect), { getString(R.string.ajustes_connect_n, Connect.quantosAutorizados()) }) {
            Painel.mostrar(this, getString(R.string.ajustes_connect), listOf(
                Painel.Item("Endereço e PIN", "${Connect.obterIpLocal()} · PIN ${Connect.pinAtual}") {},
                Painel.Item("Conexão na rede local", if (Connect.ehAutoAprovarAtivo(this)) "Direta (recomendado)" else "Exigir PIN", fecha = false) {
                    Connect.definirAutoAprovar(this, !Connect.ehAutoAprovarAtivo(this))
                    redesenhar()
                },
                Painel.Item(getString(R.string.ajustes_connect_esquecer)) {
                    Connect.esquecerTodos(this)
                    Toast.makeText(this, R.string.ajustes_connect_esquecido, Toast.LENGTH_LONG).show()
                    redesenhar()
                }
            ))
        },

        Secao(getString(R.string.ajustes_sistema)),
        Opcao(getString(R.string.ajustes_atualizar), { "v${BuildConfig.VERSION_NAME}" }) { procurarVersao() },
        Opcao(getString(R.string.ajustes_recarregar), { getString(R.string.ajustes_recarregar_dica) }) { recarregar() },
        Opcao(getString(R.string.ajustes_telemetria), { simNao(Preferencias.telemetria) }) {
            Preferencias.telemetria = !Preferencias.telemetria; redesenhar()
        },
    )

    private fun <T> escolher(titulo: String, valores: List<T>, atual: T, nome: (T) -> String, aoEscolher: (T) -> Unit) {
        Painel.mostrar(this, titulo, valores.map { v ->
            Painel.Item(nome(v), marcado = v == atual) { aoEscolher(v); redesenhar() }
        })
    }

    /** Linha que tinha o foco: depois de trocar um valor, ele volta para ela. */
    private var posicaoEmFoco = RecyclerView.NO_POSITION

    // Sem isto o foco caía no "Buscar" do topo depois de cada OK: a lista
    // recriava as linhas e a que estava focada sumia.
    private fun redesenhar() {
        val antes = lista.focusedChild?.let { lista.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: posicaoEmFoco
        adaptador.linhas = linhas()
        if (antes != RecyclerView.NO_POSITION) {
            lista.post { lista.findViewHolderForAdapterPosition(antes)?.itemView?.requestFocus() }
        }
    }

    private fun procurarVersao() {
        lifecycleScope.launch(semDerrubar) {
            val versao = Atualizacao.procurar(this@AjustesActivity, manual = true)
            if (versao == null) {
                Toast.makeText(this@AjustesActivity, R.string.ajustes_em_dia, Toast.LENGTH_LONG).show()
            } else {
                atualizacao.ofertar()
            }
        }
    }

    /** Apaga o catálogo e as capas guardadas: a próxima abertura baixa tudo de novo. */
    private fun recarregar() {
        lifecycleScope.launch(semDerrubar) {
            withContext(Dispatchers.IO) {
                File(filesDir, "vod").deleteRecursively()
                runCatching { coil3.SingletonImageLoader.get(this@AjustesActivity).diskCache?.clear() }
                Remote.refresh(this@AjustesActivity, forcar = true)
            }
            runCatching { coil3.SingletonImageLoader.get(this@AjustesActivity).memoryCache?.clear() }
            Toast.makeText(this@AjustesActivity, R.string.ajustes_recarregado, Toast.LENGTH_LONG).show()
        }
    }

    private inner class Adaptador : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var linhas: List<Linha> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        // Ids estáveis: ao redesenhar, a mesma linha continua sendo a mesma
        // vista, e o foco do controle não se perde.
        init { setHasStableIds(true) }

        override fun getItemId(position: Int) = position.toLong()

        override fun getItemViewType(position: Int) = if (linhas[position] is Secao) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.item_ajuste_secao else R.layout.item_painel
            val vista = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return object : RecyclerView.ViewHolder(vista) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val linha = linhas[position]) {
                is Secao -> (holder.itemView as TextView).text = linha.nome
                is Opcao -> {
                    holder.itemView.findViewById<TextView>(R.id.itemTexto).text = linha.nome
                    holder.itemView.findViewById<TextView>(R.id.itemDetalhe).apply {
                        text = linha.valor(); visibility = View.VISIBLE
                    }
                    holder.itemView.nextFocusLeftId = R.id.ajCatMais
                    holder.itemView.setOnClickListener { linha.acao() }
                    holder.itemView.setOnFocusChangeListener { _, tem ->
                        if (tem) posicaoEmFoco = holder.bindingAdapterPosition
                    }
                }
            }
        }

        override fun getItemCount() = linhas.size
    }
}
