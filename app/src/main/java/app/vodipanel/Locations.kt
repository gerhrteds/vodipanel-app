package app.vodipanel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

/** A server endpoint of a country that the phone pings directly (plain TCP connect time). */
data class Probe(val host: String, val port: Int)

/** One country that has healthy servers on the panel. */
data class Loc(val cc: String, val name: String, val count: Int, val serverMs: Int?, val probes: List<Probe>)

/** ms > 0: measured from this phone; ms == -1: probes did not answer; est: only the panel's own measurement is known. */
data class PingR(val ms: Int, val est: Boolean = false)

object Locations {
    fun parse(text: String): List<Loc> {
        val arr = JSONObject(text).optJSONArray("locations") ?: return emptyList()
        val out = ArrayList<Loc>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cc = o.optString("cc")
            if (cc.length != 2) continue
            val probes = ArrayList<Probe>()
            val pr = o.optJSONArray("probes")
            if (pr != null) for (j in 0 until pr.length()) {
                val p = pr.optJSONObject(j) ?: continue
                val h = p.optString("h")
                val port = p.optInt("p")
                if (h.isNotBlank() && port in 1..65535) probes.add(Probe(h, port))
            }
            out.add(Loc(cc.uppercase(), o.optString("name").ifBlank { cc }, o.optInt("count"),
                if (o.isNull("ms")) null else o.optInt("ms"), probes))
        }
        return out
    }

    /** Blocking: run on Dispatchers.IO. Throws ApiException with the server's Persian message. */
    fun fetch(): List<Loc> {
        val token = Prefs.subToken.ifBlank { "public" }
        return parse(Api.request("/api/locations?token=" + URLEncoder.encode(token, "UTF-8")))
    }

    /** The real (non-VPN) network, so the ping stays honest even while the VPN is connected. */
    private fun underlying(ctx: Context): android.net.Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        var best: android.net.Network? = null
        var bestScore = -1
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            var s = 0
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 100
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 20
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 10
            if (s > bestScore) { best = n; bestScore = s }
        }
        return best
    }

    /** TCP connect time in ms (DNS is resolved before the clock starts), or null if it fails. */
    fun tcpPing(ctx: Context, host: String, port: Int, timeoutMs: Int = 2000): Int? {
        val sock: Socket = try { underlying(ctx)?.socketFactory?.createSocket() ?: Socket() } catch (e: Exception) { Socket() }
        return try {
            val addr = InetSocketAddress(host, port)
            if (addr.isUnresolved) return null
            val t0 = System.nanoTime()
            sock.connect(addr, timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            null
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** Best ping over the country's probe endpoints; falls back to the panel's own number for UDP-only countries. */
    fun measure(ctx: Context, l: Loc): PingR {
        var best = Int.MAX_VALUE
        for (p in l.probes) {
            val ms = tcpPing(ctx, p.host, p.port)
            if (ms != null && ms < best) best = ms
            if (best < Int.MAX_VALUE) break
        }
        if (best < Int.MAX_VALUE) return PingR(best)
        if (l.probes.isEmpty() && l.serverMs != null) return PingR(l.serverMs, est = true)
        return PingR(-1)
    }
}
