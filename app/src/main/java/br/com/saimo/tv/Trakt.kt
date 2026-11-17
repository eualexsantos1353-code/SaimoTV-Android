package br.com.saimo.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Trakt (trakt.tv), só para quem entra com a própria conta. Só lê.
 *
 * A entrada é o código de dispositivo do Trakt com PKCE: a TV mostra um código curto e a pessoa o digita
 * em trakt.tv/activate no celular. Só o Client ID é preciso (ele entra no app pelo segredo TRAKT_CLIENT_ID
 * do GitHub Actions; fora do Git); nenhum segredo de aplicativo vai no APK. Os tokens ficam cifrados no [Cofre].
 *
 * Sem Client ID no build, [disponivel] é falso e nada aqui roda.
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private const val TAG = "Trakt"

    // Entrada em andamento (a tela Nuvio mostra o código); lidos da tela, escritos pela linha de fundo.
    @Volatile var codigo: String? = null
        private set
    @Volatile var endereco: String = "trakt.tv/activate"
        private set
    @Volatile var conectando: Boolean = false
        private set
    /** Como terminou a última tentativa ("" quando não há o que dizer). */
    @Volatile var mensagem: String = ""
        private set
    @Volatile private var geracao = 0
    private val trava = Any()

    private fun clientId() = BuildConfig.TRAKT_CLIENT_ID.trim()

    fun disponivel() = clientId().isNotEmpty()

    fun conectado(c: Context) = disponivel() && Cofre.ler(c, "refresh") != null

    /** O nome da conta ("" se não houver). */
    fun usuario(c: Context) = if (conectado(c)) Cofre.ler(c, "usuario").orEmpty() else ""

    // MARK: - Entrar e sair

    /** Começa a entrada em segundo plano; [aoMudar] roda (na linha de fundo) a cada passo. */
    fun entrar(c: Context, aoMudar: () -> Unit) {
        val minha = synchronized(trava) {
            if (conectando || !disponivel()) return
            conectando = true
            geracao++
            geracao
        }
        codigo = null
        mensagem = ""
        val app = c.applicationContext
        Thread({
            try {
                val aleatorio = ByteArray(48).also { SecureRandom().nextBytes(it) }
                val verificador = base64Url(aleatorio)
                val desafio = base64Url(MessageDigest.getInstance("SHA-256").digest(verificador.toByteArray(Charsets.US_ASCII)))
                val inicio = enviar("POST", "/oauth/device/code", JSONObject().put("client_id", clientId())
                    .put("code_challenge", desafio).put("code_challenge_method", "S256"), null)
                if (inicio.first != 200) throw IOException("HTTP ${inicio.first}")
                val dados = JSONObject(inicio.second)
                codigo = dados.getString("user_code")
                endereco = dados.optString("verification_url", "https://trakt.tv/activate").replaceFirst(Regex("^https?://"), "")
                aoMudar()
                val dispositivo = dados.getString("device_code")
                var intervalo = maxOf(5L, dados.optLong("interval", 5)) * 1000L
                val ate = System.currentTimeMillis() + dados.optLong("expires_in", 600) * 1000L
                while (System.currentTimeMillis() < ate) {
                    Thread.sleep(intervalo)
                    if (minha != geracao) return@Thread   // cancelada, ou uma nova entrada começou
                    val r = enviar("POST", "/oauth/device/token", JSONObject().put("code", dispositivo)
                        .put("client_id", clientId()).put("code_verifier", verificador), null)
                    when (r.first) {
                        200 -> {
                            if (!guardar(app, JSONObject(r.second))) {
                                mensagem = "Não foi possível guardar o login com segurança nesta TV."
                                return@Thread
                            }
                            val nome = try {
                                JSONObject(pegar(app, "/users/settings")).getJSONObject("user").optString("username")
                            } catch (e: Exception) { "" }
                            Cofre.guardar(app, "usuario", nome)
                            Log.i(TAG, "conectado")
                            return@Thread
                        }
                        400 -> Unit                                 // ainda não digitado
                        429 -> intervalo += 1000                    // rápido demais
                        418 -> { mensagem = "A conexão foi recusada no Trakt."; return@Thread }
                        410 -> break                                // expirou
                        else -> throw IOException("HTTP ${r.first}")
                    }
                }
                mensagem = "O código expirou. Peça um novo."
            } catch (e: Exception) {
                // O motivo (o código HTTP ou o tipo do erro de rede), para a tela dizer o que houve; nada de segredo.
                val motivo = e.message?.takeIf { it.startsWith("HTTP") } ?: e.javaClass.simpleName
                mensagem = "Não foi possível conectar ao Trakt ($motivo). Tente de novo."
                Log.w(TAG, "entrar: ${e.message}")
            } finally {
                synchronized(trava) { if (minha == geracao) conectando = false }
                if (minha == geracao) aoMudar()
            }
        }, "trakt").start()
    }

    /** Desiste da entrada em andamento (a pessoa saiu da tela ou cancelou). */
    fun cancelar() {
        synchronized(trava) { geracao++; conectando = false }
        codigo = null
    }

    private fun guardar(c: Context, t: JSONObject): Boolean {
        val criado = t.optLong("created_at", System.currentTimeMillis() / 1000)
        val expira = (criado + t.optLong("expires_in", 86_400)) * 1000L
        return Cofre.guardar(c, "access", t.optString("access_token")) &&
            Cofre.guardar(c, "refresh", t.optString("refresh_token")) &&
            Cofre.guardar(c, "expira", expira.toString())
    }

    // MARK: - Token e chamadas

    /** Um token válido, renovado perto de vencer (os do Trakt duram um dia). Fora da linha da tela. */
    @Synchronized
    private fun token(c: Context): String {
        val refresh = Cofre.ler(c, "refresh") ?: throw IOException("sem login")
        val expira = Cofre.ler(c, "expira")?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() < expira - 3_600_000L) return Cofre.ler(c, "access") ?: throw IOException("sem login")
        val r = enviar("POST", "/oauth/token", JSONObject().put("refresh_token", refresh).put("client_id", clientId())
            .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"), null)
        if (r.first == 400 || r.first == 401) {
            // A conta foi desconectada no trakt.tv: aqui também.
            Cofre.limpar(c)
            throw IOException("o Trakt encerrou o login desta TV")
        }
        if (r.first != 200) throw IOException("HTTP ${r.first}")
        if (!guardar(c, JSONObject(r.second))) throw IOException("sem como guardar o login")
        return Cofre.ler(c, "access") ?: throw IOException("sem login")
    }

    /** GET com o login da pessoa; devolve o corpo. Fora da linha da tela. */
    fun pegar(c: Context, caminho: String): String {
        val r = enviar("GET", caminho, null, token(c))
        if (r.first != 200) throw IOException("HTTP ${r.first}")
        return r.second
    }

    /** GET sem login (calendários públicos); só o Client ID. Fora da linha da tela. */
    fun publico(caminho: String): String {
        if (!disponivel()) throw IOException("sem Client ID")
        val r = enviar("GET", caminho, null, null)
        if (r.first != 200) throw IOException("HTTP ${r.first}")
        return r.second
    }

    // MARK: - HTTP

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun enviar(metodo: String, caminho: String, corpo: JSONObject?, token: String?): Pair<Int, String> {
        val h = URL(API + caminho).openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 8_000
            h.readTimeout = 15_000
            h.instanceFollowRedirects = false   // o token não pode seguir um redirecionamento para outro servidor
            h.requestMethod = metodo
            h.setRequestProperty("Content-Type", "application/json")
            h.setRequestProperty("User-Agent", "ASTRAL-TV")
            h.setRequestProperty("trakt-api-version", "2")
            h.setRequestProperty("trakt-api-key", clientId())
            if (token != null) h.setRequestProperty("Authorization", "Bearer $token")
            if (corpo != null) {
                h.doOutput = true
                h.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
            }
            val codigoHttp = h.responseCode
            val fluxo = if (codigoHttp < 400) h.inputStream else h.errorStream
            val texto = fluxo?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            return codigoHttp to texto
        } finally {
            h.disconnect()
        }
    }
}
