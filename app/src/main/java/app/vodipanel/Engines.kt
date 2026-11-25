package app.vodipanel

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * An Engine produces a complete sing-box config (JSON). The panel server builds it from the healthy
 * proxies/cores the owner added, so the owner can change servers without releasing a new APK.
 */
interface Engine {
    val id: String
    /** Short Persian name shown while connecting. */
    val label: String
    /** How long to wait for real traffic through the tunnel before giving up on this engine. */
    val verifyTimeoutMs: Long get() = 15000
    suspend fun prepare(ctx: Context): String
    suspend fun stop() {}
}

class PanelEngine : Engine {
    override val id = "panel"
    override val label = "سرورهای اصلی"
    override val verifyTimeoutMs = 25000L // urltest needs a moment to pick a healthy server
    override suspend fun prepare(ctx: Context): String {
        val token = Prefs.subToken.ifBlank { "public" }
        val cc = Prefs.selectedCc
        val res = Api.request("/sub/" + java.net.URLEncoder.encode(token, "UTF-8") +
            (if (cc.length == 2) "?cc=" + java.net.URLEncoder.encode(cc, "UTF-8") else ""))
        return JSONObject(res).getJSONObject("config").toString()
    }
}

/**
 * Free backup path through Cloudflare WARP (WireGuard). Only used if the panel engine could not be verified.
 * The same free account is tried on several of Cloudflare's UDP ports, because ISPs often block just one of them.
 */
class WarpEngine(private val port: Int) : Engine {
    override val id = "warp-$port"
    override val label = "Cloudflare WARP ($port)"
    override suspend fun prepare(ctx: Context): String = WarpReg.buildConfig(WarpReg.get(), port)
}

object VodiController {
    private val WARP_PORTS = listOf(2408, 500, 4500)

    fun engines(): List<Engine> = buildList {
        add(PanelEngine())
        // a customer who picked a country must never be silently moved to another one, so no WARP backup then
        if (Prefs.warpFallback && Prefs.selectedCc.length != 2) WARP_PORTS.forEach { add(WarpEngine(it)) }
    }
}

object WarpReg {
    private const val PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Returns the cached registration or creates a new free WARP account (one HTTPS call). */
    fun get(): JSONObject {
        runCatching { JSONObject(Prefs.warpReg) }.getOrNull()
            ?.takeIf { it.has("private_key") && it.has("v4") }?.let { return it }
        try {
            val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val pub = X25519.scalarMult(priv, X25519.BASE)
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val body = JSONObject().put("key", b64(pub)).put("install_id", "").put("fcm_token", "")
                .put("tos", fmt.format(Date())).put("type", "Android").put("model", "PC").put("locale", "en_US").toString()
            val res = JSONObject(Api.fetch("https://api.cloudflareclient.com/v0a2158/reg", body, mapOf(
                "Content-Type" to "application/json", "User-Agent" to "okhttp/3.12.1", "CF-Client-Version" to "a-6.3-2158")))
            val cfg = res.getJSONObject("config")
            val peer = cfg.getJSONArray("peers").getJSONObject(0)
            val v4 = cfg.getJSONObject("interface").getJSONObject("addresses").getString("v4")
            val reserved = JSONArray()
            val cid = cfg.optString("client_id")
            if (cid.isNotBlank()) Base64.decode(cid, Base64.DEFAULT).take(3).forEach { reserved.put(it.toInt() and 0xFF) }
            val out = JSONObject().put("private_key", b64(priv)).put("v4", v4)
                .put("peer_key", peer.optString("public_key").ifBlank { PEER_KEY }).put("reserved", reserved)
            Prefs.warpReg = out.toString()
            return out
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(0, "ثبت‌نام Cloudflare WARP ناموفق بود")
        }
    }

    fun buildConfig(r: JSONObject, port: Int = 2408): String {
        val v4 = r.getString("v4").let { if (it.contains("/")) it else "$it/32" }
        val warp = JSONObject().put("type", "wireguard").put("tag", "warp")
            .put("server", "engage.cloudflareclient.com").put("server_port", port)
            .put("local_address", JSONArray().put(v4)).put("private_key", r.getString("private_key"))
            .put("peer_public_key", r.getString("peer_key")).put("mtu", 1280)
        val reserved = r.optJSONArray("reserved")
        if (reserved != null && reserved.length() == 3) warp.put("reserved", reserved)
        val base = JSONObject("""
            {"log":{"level":"warn"},
             "dns":{"servers":[{"tag":"remote","address":"https://1.1.1.1/dns-query","detour":"warp"},
                               {"tag":"local","address":"local","detour":"direct"}],
                    "rules":[{"outbound":"any","server":"local"}],"strategy":"ipv4_only"},
             "inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true,"strict_route":true,"stack":"mixed"}],
             "route":{"auto_detect_interface":true,"final":"warp",
                      "rules":[{"action":"sniff"},{"protocol":"dns","action":"hijack-dns"},{"ip_is_private":true,"outbound":"direct"}]}}
        """.trimIndent())
        base.put("outbounds", JSONArray().put(warp).put(JSONObject().put("type", "direct").put("tag", "direct")))
        return base.toString()
    }
}

/** X25519 (RFC 7748) for WireGuard keys. Checked against the RFC test vectors. */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val TWO: BigInteger = BigInteger.valueOf(2)
    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    private fun dec(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun enc(n: BigInteger): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val idx = be.size - 1 - i
            out[i] = if (idx >= 0) be[idx] else 0
        }
        return out
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        val c = k.copyOf()
        c[0] = (c[0].toInt() and 248).toByte()
        c[31] = ((c[31].toInt() and 127) or 64).toByte()
        val kk = dec(c)
        val ub = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = dec(ub).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (kk.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val cc = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = cc.multiply(b).mod(P)
            x3 = da.add(cb).modPow(TWO, P)
            z3 = x1.multiply(da.subtract(cb).modPow(TWO, P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        return enc(x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P))
    }
}
