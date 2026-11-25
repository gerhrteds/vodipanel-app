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
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("vodi", Context.MODE_PRIVATE) }

    val panelUrl: String get() = BuildConfig.PANEL_URL.trimEnd('/')

    /** Optional customer subscription code. Blank = public mode. */
    var subToken: String
        get() = sp.getString("token", "")!!
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Last /api/app-config response, so the theme is applied instantly on next launch. */
    var cfgJson: String
        get() = sp.getString("cfg", "")!!
        set(v) = sp.edit().putString("cfg", v).apply()
}

class ApiException(val code: Int, message: String) : Exception(message)

object Api {
    /** Blocking HTTP call - run on Dispatchers.IO. Throws ApiException with the server's Persian message. */
    fun request(path: String): String {
        val c = URL(Prefs.panelUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 20000
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
