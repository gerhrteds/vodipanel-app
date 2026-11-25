package app.vodipanel

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class Status { OFF, CONNECTING, ON, ERROR }

object VodiState {
    val status = MutableStateFlow(Status.OFF)
    val message = MutableStateFlow("")
    /** Epoch millis when the tunnel came up (for the connection timer). */
    val since = MutableStateFlow(0L)
    /** Id of the engine that is currently carrying the tunnel ("panel" or "warp..."). */
    val engine = MutableStateFlow("")
    /** Exit IP / country measured by the connection check BEFORE the status became ON (null while not verified). */
    val exit = MutableStateFlow<ExitInfo?>(null)
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("vodi", Context.MODE_PRIVATE) }

    val panelUrl: String get() = BuildConfig.PANEL_URL.trimEnd('/')

    /** Optional customer subscription code. Blank = public mode. */
    var subToken: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Use free Cloudflare WARP as a backup when the panel has no healthy server. */
    var warpFallback: Boolean
        get() = sp.getBoolean("warp_fb", true)
        set(v) = sp.edit().putBoolean("warp_fb", v).apply()

    /** Cached Cloudflare WARP registration (JSON), so we register only once per install. */
    var warpReg: String
        get() = sp.getString("warp_reg", "")!!
        set(v) = sp.edit().putString("warp_reg", v).apply()

    /** Last /api/app-config response, so the theme is applied instantly on next launch. */
    var cfgJson: String
        get() = sp.getString("cfg", "")!!
        set(v) = sp.edit().putString("cfg", v).apply()
}

class ApiException(val code: Int, message: String) : Exception(message)

object Api {
    /** Blocking HTTP call to an absolute https URL (GET, or POST when [body] is given). */
    fun fetch(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = if (body != null) "POST" else "GET"
        c.connectTimeout = 12000
        c.readTimeout = 15000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw ApiException(code, "خطای سرویس ($code)")
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Blocking HTTP call - run on Dispatchers.IO. Throws ApiException with the server's Persian message. */
    fun request(path: String, connectMs: Int = 15000, readMs: Int = 20000): String {
        val c = URL(Prefs.panelUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                throw ApiException(code, msg.ifBlank { "خطای سرور ($code)" })
            }
            return text
        } finally {
            c.disconnect()
        }
    }
}

fun loadBitmap(path: String): android.graphics.Bitmap? = try {
    val u = if (path.startsWith("http")) path else Prefs.panelUrl + path
    URL(u).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
} catch (e: Exception) { null }

fun flag(cc: String?): String =
    if (cc == null || cc.length != 2) "🌐"
    else String(Character.toChars(0x1F1E6 + (cc[0].uppercaseChar() - 'A'))) +
         String(Character.toChars(0x1F1E6 + (cc[1].uppercaseChar() - 'A')))

// ------------------------------------------------------------------------------------------ exit IP / country

data class ExitInfo(val ip: String, val cc: String?, val country: String?, val city: String?, val isp: String?, val ms: Long)

object ExitProbe {
    private fun clean(s: String?): String? = s?.takeIf { it.isNotBlank() && it != "null" }

    /** Asks the panel (through the tunnel) which IP/country the traffic leaves from; falls back to ipwho.is. */
    suspend fun fetch(): ExitInfo? {
        for (attempt in 1..3) {
            val t0 = System.currentTimeMillis()
            val a = try { JSONObject(Api.request("/api/myip")) } catch (e: Exception) { null }
            val ms = System.currentTimeMillis() - t0
            val ip = clean(a?.optString("ip"))
            if (a != null && ip != null) {
                val info = ExitInfo(ip, clean(a.optString("cc")), clean(a.optString("country")),
                    clean(a.optString("city")), clean(a.optString("isp")), ms)
                return if (info.country == null) (lookup(ip, ms) ?: info) else info
            }
            lookup(null, ms)?.let { return it }
            kotlinx.coroutines.delay(2000)
        }
        return null
    }

    private fun lookup(ip: String?, ms: Long): ExitInfo? = try {
        val j = JSONObject(Api.fetch("https://ipwho.is/" + (ip ?: "")))
        val found = clean(j.optString("ip"))
        if (!j.optBoolean("success", false) || found == null) null
        else ExitInfo(found, clean(j.optString("country_code")), clean(j.optString("country")), clean(j.optString("city")),
            clean(j.optJSONObject("connection")?.optString("isp")), ms)
    } catch (e: Exception) { null }
}
