package br.com.saimo.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * O aplicativo Nuvio instalado na TV. O Saimo não tem player nem catálogo do Nuvio: só o abre.
 * Os pacotes são os que o Free My TV já usa (o da TV e o do Play Store).
 */
object Nuvio {
    private val PACOTES = listOf("com.nuvio.tv", "com.nuvio.app")

    enum class Resultado { ABRIU, SEM_APP, FALHOU }

    private fun intentDe(pm: PackageManager, pacote: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)

    /**
     * Abre um título no Nuvio pelo link dele (IMDb, ou TMDB quando não há). Se o Nuvio não resolver o
     * link, abre só o aplicativo. [Resultado.SEM_APP] quando nenhum dos pacotes está instalado.
     */
    fun abrirTitulo(contexto: Context, serie: Boolean, imdb: String?, tmdb: Long?): Resultado {
        val pm = contexto.packageManager
        val pacote = PACOTES.firstOrNull { intentDe(pm, it) != null } ?: return Resultado.SEM_APP
        val tipo = if (serie) "series" else "movie"
        val endereco = when {
            !imdb.isNullOrBlank() -> "nuvio://detail/$tipo/$imdb"
            tmdb != null -> "nuvio://tmdb/$tipo/$tmdb"
            else -> return abrir(contexto)
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(endereco))
            .setPackage(pacote).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(pm) == null) return abrir(contexto)
        return try {
            contexto.startActivity(intent)
            Resultado.ABRIU
        } catch (e: Exception) {
            abrir(contexto)
        }
    }

    /** Abre o Nuvio. [Resultado.SEM_APP] quando nenhum dos pacotes está instalado. */
    fun abrir(contexto: Context): Resultado {
        val pm = contexto.packageManager
        val intent = PACOTES.firstNotNullOfOrNull { intentDe(pm, it) } ?: return Resultado.SEM_APP
        return try {
            contexto.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Resultado.ABRIU
        } catch (e: Exception) {
            Resultado.FALHOU
        }
    }
}
