package br.com.saimo.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A Agenda (etapa 4): calendário do mês à esquerda e o painel do dia à direita.
 *
 * Os dados vêm de [AgendaDados] (Trakt e TMDB); o guardado aparece na hora e a busca atualiza depois.
 * Setas: esquerda/direita andam de dia em dia, cima/baixo de semana em semana; ao passar para um dia de
 * outro mês, o calendário vira o mês. OK num título abre no Nuvio. Nada é inventado: sem dados, o painel diz.
 */
@UnstableApi
class AgendaActivity : TelaComMenu() {

    override val semMenu = true

    private lateinit var menu: MenuLateral
    private val celulas = ArrayList<View>()
    private val filtros = ArrayList<TextView>()
    private lateinit var lista: RecyclerView
    private lateinit var diaTitulo: TextView
    private lateinit var estado: TextView
    private lateinit var mesTitulo: TextView
    private lateinit var aviso: TextView

    private var todos: List<AgendaDados.Item> = emptyList()
    private var filtrada: List<AgendaDados.Item> = emptyList()
    private var dias: Set<Long> = emptySet()
    private var filtro = AgendaDados.Filtro.PROXIMOS
    private var mes = 0L
    private var selecionado = 0L
    private var carregando = false
    private var erro = false
    private var ultimaCarga = 0L

    private val ptBr = Locale("pt", "BR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agenda)

        lista = findViewById(R.id.agendaLista)
        lista.layoutManager = LinearLayoutManager(this)
        diaTitulo = findViewById(R.id.agendaDiaTitulo)
        estado = findViewById(R.id.agendaEstado)
        mesTitulo = findViewById(R.id.agendaMesTitulo)
        aviso = findViewById(R.id.agendaAviso)

        for (id in listOf(R.id.agendaFiltro0, R.id.agendaFiltro1, R.id.agendaFiltro2, R.id.agendaFiltro3)) {
            filtros += findViewById<TextView>(id)
        }
        menu = MenuLateral(this, findViewById(R.id.agendaMenu), MenuPrincipal.itens(this, Destino.AGENDA) {}, filtros[0]) {}
        filtros[0].nextFocusLeftId = menu.primeiroId
        filtros.forEachIndexed { i, f ->
            f.isSelected = i == 0
            f.setOnClickListener { escolherFiltro(AgendaDados.Filtro.values()[i]) }
        }

        val anterior = findViewById<View>(R.id.agendaMesAnterior)
        anterior.nextFocusLeftId = menu.primeiroId
        anterior.setOnClickListener { virarMes(-1) }
        findViewById<View>(R.id.agendaMesProximo).setOnClickListener { virarMes(1) }

        montarSemana()
        montarGrade()

        val hoje = AgendaDados.inicioDoDia(System.currentTimeMillis())
        selecionado = hoje
        todos = AgendaDados.guardada(this)
        aplicarFiltro()
        mostrarMes(hoje)
        focarQuandoPronto { celulas[indiceDe(hoje)] }
        carregarAgenda()
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - ultimaCarga > 600_000L) carregarAgenda()
    }

    // MARK: - Montagem

    private fun montarSemana() {
        val caixa = findViewById<LinearLayout>(R.id.agendaSemana)
        for (nome in arrayOf("D", "S", "T", "Q", "Q", "S", "S")) {
            caixa.addView(TextView(this).apply {
                text = nome
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                setTextColor(corDoTema(R.attr.astralTextoSecundario))
                layoutParams = LinearLayout.LayoutParams((56 * resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    private fun montarGrade() {
        val grade = findViewById<LinearLayout>(R.id.agendaGrade)
        val inflador = LayoutInflater.from(this)
        for (linha in 0 until 6) {
            val caixa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (coluna in 0 until 7) {
                val indice = linha * 7 + coluna
                val cel = inflador.inflate(R.layout.item_agenda_dia, caixa, false)
                cel.id = View.generateViewId()
                if (coluna == 0) cel.nextFocusLeftId = menu.primeiroId
                if (linha == 5) cel.nextFocusDownId = cel.id
                cel.setOnFocusChangeListener { v, foco ->
                    if (!foco) return@setOnFocusChangeListener
                    val dia = v.tag as? Long ?: return@setOnFocusChangeListener
                    selecionar(dia)
                    // Um dia de outro mês: o calendário vira o mês e o foco acompanha o dia.
                    if (AgendaDados.inicioDoMes(dia) != mes) v.post { mostrarMes(dia); focarDia(dia) }
                }
                cel.setOnClickListener { primeiroDaLista() }
                cel.setOnKeyListener { _, codigo, evento ->
                    if (coluna == 6 && codigo == KeyEvent.KEYCODE_DPAD_RIGHT && evento.action == KeyEvent.ACTION_DOWN) {
                        primeiroDaLista()
                        true
                    } else false
                }
                celulas += cel
                caixa.addView(cel)
            }
            grade.addView(caixa)
        }
    }

    // MARK: - Calendário

    private fun inicioDaGrade(): Long {
        val dow = Calendar.getInstance().apply { timeInMillis = mes }.get(Calendar.DAY_OF_WEEK) - 1
        return AgendaDados.somarDias(mes, -dow)
    }

    private fun indiceDe(dia: Long) = AgendaDados.diasEntre(inicioDaGrade(), dia).coerceIn(0, 41)

    private fun mostrarMes(qualquerDia: Long) {
        mes = AgendaDados.inicioDoMes(qualquerDia)
        mesTitulo.text = maiuscula(SimpleDateFormat("MMMM 'de' yyyy", ptBr).format(Date(mes)))
        pintarGrade()
    }

    private fun pintarGrade() {
        val hoje = AgendaDados.inicioDoDia(System.currentTimeMillis())
        val inicio = inicioDaGrade()
        val calendario = Calendar.getInstance()
        for (i in 0 until 42) {
            val dia = AgendaDados.somarDias(inicio, i)
            val cel = celulas[i]
            calendario.timeInMillis = dia
            cel.tag = dia
            val numero = cel.findViewById<TextView>(R.id.agendaDiaNumero)
            numero.text = calendario.get(Calendar.DAY_OF_MONTH).toString()
            numero.alpha = if (AgendaDados.inicioDoMes(dia) == mes) 1f else 0.4f
            cel.findViewById<View>(R.id.agendaDiaPonto).visibility = if (dia in dias) View.VISIBLE else View.INVISIBLE
            cel.isSelected = dia == selecionado
            cel.isActivated = dia == hoje
        }
    }

    private fun focarDia(dia: Long) {
        celulas[indiceDe(dia)].requestFocus()
    }

    private fun selecionar(dia: Long) {
        if (dia == selecionado) return
        selecionado = dia
        for (c in celulas) c.isSelected = (c.tag as? Long) == dia
        atualizarPainel()
    }

    private fun virarMes(passo: Int) {
        val calendario = Calendar.getInstance().apply { timeInMillis = mes; add(Calendar.MONTH, passo) }
        val novo = AgendaDados.inicioDoMes(calendario.timeInMillis)
        val hoje = AgendaDados.inicioDoDia(System.currentTimeMillis())
        selecionado = if (AgendaDados.inicioDoMes(hoje) == novo) hoje else novo
        mostrarMes(novo)
        atualizarPainel()
    }

    // MARK: - Filtro e painel

    private fun escolherFiltro(novo: AgendaDados.Filtro) {
        filtro = novo
        filtros.forEachIndexed { i, f -> f.isSelected = i == novo.ordinal }
        aplicarFiltro()
        pintarGrade()
        atualizarPainel()
    }

    private fun aplicarFiltro() {
        filtrada = AgendaDados.filtrar(todos, filtro)
        dias = filtrada.map { AgendaDados.inicioDoDia(it.em) }.toSet()
    }

    private fun atualizarPainel() {
        val itens = AgendaDados.doDia(filtrada, selecionado)
        diaTitulo.text = maiuscula(SimpleDateFormat("EEEE, d 'de' MMMM", ptBr).format(Date(selecionado)))
        val texto = when {
            carregando && todos.isEmpty() -> getString(R.string.agenda_carregando)
            erro && todos.isEmpty() -> getString(R.string.agenda_erro)
            itens.isEmpty() && filtro == AgendaDados.Filtro.PROXIMOS && !Trakt.conectado(this) && filtrada.isEmpty() ->
                getString(R.string.agenda_hint_trakt)
            itens.isEmpty() -> getString(R.string.agenda_vazio_dia)
            else -> ""
        }
        estado.text = texto
        estado.visibility = if (texto.isEmpty()) View.GONE else View.VISIBLE
        lista.adapter = AgendaEventos(itens, { abrirTitulo(it) }, { focarDia(selecionado) })
    }

    private fun primeiroDaLista() {
        lista.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
    }

    // MARK: - Dados

    private fun carregarAgenda() {
        if (carregando) return
        carregando = true
        erro = false
        atualizarPainel()
        lifecycleScope.launch {
            try {
                todos = AgendaDados.carregar(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (todos.isEmpty()) erro = true
            }
            carregando = false
            if (isDestroyed) return@launch
            ultimaCarga = System.currentTimeMillis()
            aplicarFiltro()
            pintarGrade()
            atualizarPainel()
        }
    }

    /** OK num título: abre no Nuvio (pelo id do TMDB); avisa quando não dá. */
    private fun abrirTitulo(item: AgendaDados.Item) {
        val texto = when (Nuvio.abrirTitulo(this, item.serie, null, item.id)) {
            Nuvio.Resultado.ABRIU -> null
            Nuvio.Resultado.SEM_APP -> getString(R.string.nuvio_sem_app)
            Nuvio.Resultado.FALHOU -> getString(R.string.nuvio_falha)
        }
        aviso.text = texto.orEmpty()
        aviso.visibility = if (texto == null) View.GONE else View.VISIBLE
    }

    // MARK: - Ajudantes

    private fun maiuscula(s: String) = if (s.isEmpty()) s else s.substring(0, 1).uppercase(ptBr) + s.substring(1)

    private fun corDoTema(atributo: Int): Int {
        val v = android.util.TypedValue()
        theme.resolveAttribute(atributo, v, true)
        return v.data
    }
}
