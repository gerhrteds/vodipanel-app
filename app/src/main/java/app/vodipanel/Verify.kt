package app.vodipanel

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class VerifyException(message: String) : Exception(message)

/**
 * The tunnel is only reported as "connected" after it is PROVEN to work:
 *   1. real traffic goes through it (HTTPS 204 probes answered), then
 *   2. the exit location (IP / country) is detected, and it differs from our real IP.
 * If any step fails the caller tears the tunnel down and tries the next engine.
 */
object TunnelCheck {
    private val PROBES = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")

    /** The IP the panel sees for us while NO tunnel is up (best effort, short timeouts). */
    fun baselineIp(): String? = try {
        JSONObject(Api.request("/api/myip", 5000, 5000)).optString("ip").takeIf { it.isNotBlank() && it != "null" }
    } catch (e: Exception) { null }

    private fun probe(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.instanceFollowRedirects = false
        c.useCaches = false
        try { c.responseCode == 204 } finally { c.disconnect() }
    } catch (e: Exception) { false }

    /** Step 1: waits until real traffic is exchanged through the tunnel. */
    private suspend fun traffic(timeoutMs: Long) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            for (u in PROBES) if (probe(u)) return
            delay(1200)
        }
        throw VerifyException("تونل ساخته شد ولی هیچ ترافیکی رد و بدل نشد")
    }

    /** Runs both steps; [step] receives a short Persian status text for the UI. */
    suspend fun verify(baseline: String?, trafficTimeoutMs: Long, step: (String) -> Unit): ExitInfo {
        step("در حال بررسی تبادل ترافیک…")
        traffic(trafficTimeoutMs)
        step("در حال تشخیص موقعیت…")
        val info = withTimeoutOrNull(25000) { ExitProbe.fetch() }
            ?: throw VerifyException("موقعیت (آی‌پی/کشور) تشخیص داده نشد؛ اتصال تایید نشد")
        if (baseline != null && info.ip == baseline)
            throw VerifyException("ترافیک از مسیر VPN عبور نمی‌کند (آی‌پی تغییر نکرده است)")
        return info
    }
}
