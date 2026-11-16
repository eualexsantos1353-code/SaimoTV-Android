package br.com.saimo.tv

import android.content.Context

/**
 * Os últimos canais vistos, do mais novo para o mais velho, para a aba "Últimos" do Início.
 *
 * O Início anota o último canal sintonizado cada vez que monta as fileiras (ao abrir e ao voltar do
 * player). Se a pessoa troca de canal várias vezes antes de voltar, só o último entra.
 */
object CanaisRecentes {

    private const val ARQUIVO = "inicio"
    private const val CHAVE = "canais_recentes"
    private const val MAXIMO = 12

    fun lista(context: Context): List<String> =
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
            .getString(CHAVE, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }

    fun registrar(context: Context, nome: String?) {
        if (nome.isNullOrBlank()) return
        val atual = lista(context)
        if (atual.firstOrNull() == nome) return
        val nova = (listOf(nome) + atual.filter { it != nome }).take(MAXIMO)
        context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
            .edit().putString(CHAVE, nova.joinToString("\n")).apply()
    }
}
