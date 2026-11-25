package app.vodipanel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Bridge to sing-box's `libbox` (gomobile AAR, built by the GitHub workflow into app/libs/libbox.aar).
 *
 * libbox's Java API changes slightly between sing-box releases, so everything is reached through
 * reflection: the app always compiles, and a mismatch shows up as a readable error message at connect
 * time (see logcat tag "VodiCore") instead of a build failure.
 */
object LibboxCore {
    private const val TAG = "VodiCore"
    private const val PKG = "io.nekohasekai.libbox."

    private var service: Any? = null
    private var tun: ParcelFileDescriptor? = null
    private var monitor: NetMonitor? = null
    private var setupDone = false

    @Synchronized
    fun start(vpn: VpnService, configJson: String) {
        stop()
        val libbox = try { Class.forName(PKG + "Libbox") } catch (e: ClassNotFoundException) {
            throw IllegalStateException("هسته VPN (libbox.aar) داخل برنامه نیست؛ نسخه را با GitHub Actions بسازید")
        }
        try {
            if (!setupDone) { setup(libbox, vpn); setupDone = true }
            val piClass = Class.forName(PKG + "PlatformInterface")
            val platform = Proxy.newProxyInstance(piClass.classLoader, arrayOf(piClass), PlatformHandler(vpn))
            val newService = libbox.methods.first { it.name == "newService" && it.parameterCount == 2 }
            val svc = unwrap { newService.invoke(null, configJson, platform) }
                ?: throw IllegalStateException("libbox سرویس نساخت")
            service = svc
            call(svc, "start")
        } catch (e: Throwable) {
            Log.e(TAG, "start failed", e)
            stop()
            throw IllegalStateException("خطای هسته: " + (rootCause(e).message ?: e.javaClass.simpleName))
        }
    }

    @Synchronized
    fun stop() {
        try { service?.let { call(it, "close") } } catch (e: Throwable) { Log.w(TAG, "close", e) }
        service = null
        monitor?.stop(); monitor = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
    }

    // ------------------------------------------------------------------------------------------ setup
    private fun setup(libbox: Class<*>, ctx: Context) {
        val base = ctx.filesDir.resolve("sing-box").apply { mkdirs() }
        val work = base.resolve("work").apply { mkdirs() }
        val temp = ctx.cacheDir.resolve("sing-box").apply { mkdirs() }
        val m = libbox.methods.first { it.name == "setup" }
        val p = m.parameterTypes
        if (p.size == 1) { // newer API: setup(SetupOptions)
            val o = p[0].getDeclaredConstructor().newInstance()
            trySet(o, "setBasePath", base.path); trySet(o, "setWorkingPath", work.path)
            trySet(o, "setTempPath", temp.path); trySet(o, "setFixAndroidStack", true)
            unwrap { m.invoke(null, o) }
        } else { // older API: setup(base, working, temp, isTVOS)
            val args: Array<Any?> = p.map { if (it == java.lang.Boolean.TYPE) false else "" as Any? }.toTypedArray()
            args[0] = base.path; args[1] = work.path; args[2] = temp.path
            unwrap { m.invoke(null, *args) }
        }
    }

    // ------------------------------------------------------------------------------------------ platform
    private class PlatformHandler(private val vpn: VpnService) : InvocationHandler {
        override fun invoke(proxy: Any, m: Method, a: Array<out Any?>?): Any? {
            val args = a ?: emptyArray()
            return when (m.name) {
                "usePlatformAutoDetectInterfaceControl", "usePlatformDefaultInterfaceMonitor",
                "usePlatformInterfaceGetter" -> true
                "autoDetectInterfaceControl" -> {
                    if (!vpn.protect(args[0] as Int)) throw RuntimeException("protect() failed")
                    null
                }
                "openTun" -> openTun(args[0]!!)
                "writeLog" -> { Log.d(TAG, args[0].toString()); null }
                "startDefaultInterfaceMonitor" -> {
                    monitor?.stop()
                    monitor = NetMonitor(vpn, args[0]!!).also { it.start() }
                    null
                }
                "closeDefaultInterfaceMonitor" -> { monitor?.stop(); monitor = null; null }
                "getInterfaces" -> listIterator(m.returnType, interfaces())
                "systemCertificates" -> listIterator(m.returnType, emptyList())
                "findConnectionOwner", "packageNameByUid", "uidByPackageName" ->
                    throw RuntimeException("not supported")
                "toString" -> "VodiPlatform"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args.firstOrNull()
                else -> defaultFor(m.returnType)
            }
        }

        private fun openTun(o: Any): Int {
            val b = vpn.Builder().setSession("Vodi Panel").setMtu(num(call(o, "getMTU")))
            iterate(call(o, "getInet4Address")) { b.addAddress(str(call(it, "address")), num(call(it, "prefix"))) }
            var hasV6 = false
            iterate(call(o, "getInet6Address")) { hasV6 = true; b.addAddress(str(call(it, "address")), num(call(it, "prefix"))) }
            if (call(o, "getAutoRoute") as Boolean) {
                val dns = call(o, "getDNSServerAddress")
                b.addDnsServer(if (dns is String) dns else str(call(dns!!, "getValue")))
                var any4 = false
                iterate(call(o, "getInet4RouteAddress")) { any4 = true; b.addRoute(str(call(it, "address")), num(call(it, "prefix"))) }
                if (!any4) b.addRoute("0.0.0.0", 0)
                var any6 = false
                iterate(call(o, "getInet6RouteAddress")) { any6 = true; b.addRoute(str(call(it, "address")), num(call(it, "prefix"))) }
                if (!any6 && hasV6) b.addRoute("::", 0)
            }
            val pfd = b.establish() ?: throw RuntimeException("VPN permission missing or revoked")
            tun?.close()
            tun = pfd
            return pfd.fd
        }

        private fun interfaces(): List<Any> {
            val cls = Class.forName(PKG + "NetworkInterface")
            val out = ArrayList<Any>()
            val en = java.net.NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in en) {
                try {
                    if (!ni.isUp) continue
                    val x = cls.getDeclaredConstructor().newInstance()
                    trySet(x, "setIndex", ni.index); trySet(x, "setMTU", ni.mtu); trySet(x, "setName", ni.name)
                    var flags = 1 // up
                    if (ni.isLoopback) flags = flags or 4
                    if (ni.isPointToPoint) flags = flags or 8
                    if (ni.supportsMulticast()) flags = flags or 16
                    trySet(x, "setFlags", flags)
                    val addrs = ni.interfaceAddresses.map {
                        (it.address.hostAddress ?: "").substringBefore('%') + "/" + it.networkPrefixLength
                    }
                    trySet(x, "setAddresses", listIterator(cls.getMethod("getAddresses").returnType, addrs))
                    val n = ni.name
                    trySet(x, "setType", when {
                        n.startsWith("wlan") -> "wifi"
                        n.startsWith("rmnet") || n.startsWith("ccmni") -> "cellular"
                        n.startsWith("eth") -> "ethernet"
                        else -> "other"
                    })
                    trySet(x, "setDNSServer", listIterator(cls.getMethod("getDNSServer").returnType, emptyList()))
                    trySet(x, "setMetered", false)
                    out.add(x)
                } catch (e: Throwable) {
                    Log.w(TAG, "iface ${ni.name}", e)
                }
            }
            return out
        }
    }

    // ------------------------------------------------------------------------------------------ default network tracking
    private class NetMonitor(ctx: Context, private val listener: Any) {
        private val cm = ctx.getSystemService(ConnectivityManager::class.java)
        private val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = refresh()
            override fun onLost(network: android.net.Network) = refresh()
            override fun onCapabilitiesChanged(n: android.net.Network, c: NetworkCapabilities) = refresh()
            override fun onLinkPropertiesChanged(n: android.net.Network, l: android.net.LinkProperties) = refresh()
        }
        private val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()

        fun start() { cm.registerNetworkCallback(req, cb); refresh() }
        fun stop() { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }

        private fun score(c: NetworkCapabilities): Int {
            var s = 0
            if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s += 100
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s += 30
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s += 20
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s += 10
            return s
        }

        @Synchronized
        fun refresh() {
            var name = ""
            var index = -1
            val best = cm.allNetworks.mapNotNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
                n to score(c)
            }.maxByOrNull { it.second }?.first
            if (best != null) {
                name = cm.getLinkProperties(best)?.interfaceName ?: ""
                if (name.isNotEmpty()) index = java.net.NetworkInterface.getByName(name)?.index ?: -1
            }
            try {
                val m = allTypes(listener.javaClass).flatMap { it.methods.toList() }.first { it.name == "updateDefaultInterface" }
                val args: Array<Any?> = m.parameterTypes.map { t ->
                    when (t) {
                        String::class.java -> name
                        Integer.TYPE -> index
                        java.lang.Boolean.TYPE -> false
                        else -> defaultFor(t)
                    }
                }.toTypedArray()
                m.isAccessible = true
                unwrap { m.invoke(listener, *args) }
            } catch (e: Throwable) {
                Log.w(TAG, "updateDefaultInterface", e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ reflection helpers
    private fun allTypes(c: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        var k: Class<*>? = c
        while (k != null) { out.add(k); k.interfaces.forEach { out.addAll(allTypes(it)) }; k = k.superclass }
        return out
    }

    private fun call(o: Any, name: String, vararg args: Any?): Any? {
        for (t in allTypes(o.javaClass)) {
            val m = t.methods.firstOrNull { it.name == name && it.parameterCount == args.size } ?: continue
            m.isAccessible = true
            return unwrap { m.invoke(o, *args) }
        }
        throw NoSuchMethodException(o.javaClass.name + "." + name)
    }

    private fun trySet(o: Any, name: String, v: Any?) {
        for (t in allTypes(o.javaClass)) {
            val m = t.methods.firstOrNull { it.name == name && it.parameterCount == 1 } ?: continue
            val p = m.parameterTypes[0]
            val arg: Any? = when {
                v is Int && p == java.lang.Long.TYPE -> v.toLong()
                v is Long && p == Integer.TYPE -> v.toInt()
                else -> v
            }
            try { m.isAccessible = true; m.invoke(o, arg) } catch (e: Exception) { Log.w(TAG, "set $name", e) }
            return
        }
    }

    private fun iterate(it: Any?, f: (Any) -> Unit) {
        if (it == null) return
        while (call(it, "hasNext") as Boolean) f(call(it, "next")!!)
    }

    private fun num(v: Any?): Int = (v as Number).toInt()
    private fun str(v: Any?): String = v as String

    private fun <T> unwrap(f: () -> T): T = try { f() } catch (e: InvocationTargetException) { throw e.cause ?: e }

    private fun rootCause(t: Throwable): Throwable {
        var c = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
    }

    private fun defaultFor(t: Class<*>): Any? = when (t) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        String::class.java -> ""
        else -> null
    }

    /** Builds an object implementing a libbox iterator interface (StringIterator, NetworkInterfaceIterator...). */
    private fun listIterator(type: Class<*>, items: List<Any?>): Any {
        var i = 0
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { p, m, a ->
            when (m.name) {
                "hasNext" -> i < items.size
                "next" -> items[i++]
                "len" -> items.size
                "toString" -> "VodiIterator"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a?.firstOrNull()
                else -> defaultFor(m.returnType)
            }
        }
    }
}
