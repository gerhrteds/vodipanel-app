package app.vodipanel

import android.content.Context
import org.json.JSONObject

/**
 * An Engine produces a complete sing-box config (JSON). The panel server builds it from the healthy
 * proxies/cores the owner added, so the owner can change servers without releasing a new APK.
 */
interface Engine {
    val id: String
    suspend fun prepare(ctx: Context): String
    suspend fun stop() {}
}

class PanelEngine : Engine {
    override val id = "panel"
    override suspend fun prepare(ctx: Context): String {
        val token = Prefs.subToken.ifBlank { "public" }
        val res = Api.request("/sub/" + java.net.URLEncoder.encode(token, "UTF-8"))
        return JSONObject(res).getJSONObject("config").toString()
    }
}

object VodiController {
    fun engines(): List<Engine> = listOf(PanelEngine())
}
