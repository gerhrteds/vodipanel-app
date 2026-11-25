package app.vodipanel

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startVpn() else VodiState.status.value = Status.OFF
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { Root(onToggle = ::toggle) }
    }

    private fun toggle() {
        when (VodiState.status.value) {
            Status.ON, Status.CONNECTING ->
                startService(Intent(this, VodiVpnService::class.java).setAction("STOP"))
            else -> {
                val i = VpnService.prepare(this)
                if (i != null) vpnPermission.launch(i) else startVpn()
            }
        }
    }

    private fun startVpn() {
        VodiState.status.value = Status.CONNECTING
        ContextCompat.startForegroundService(this, Intent(this, VodiVpnService::class.java))
    }
}

// ------------------------------------------------------------------------------------------ theme from server

data class Pal(val primary: Color, val secondary: Color, val bg1: Color, val bg2: Color,
               val card: Color, val text: Color, val muted: Color)

val DefaultPal = Pal(Color(0xFFA855F7), Color(0xFF7C3AED), Color(0xFF07030F), Color(0xFF1A0B3B),
    Color(0xFF160D2E), Color(0xFFF5F0FF), Color(0xFFA99CCB))
val LocalPal = staticCompositionLocalOf { DefaultPal }

fun parsePal(t: JSONObject?): Pal {
    fun c(k: String, d: Color): Color = try { Color(android.graphics.Color.parseColor(t!!.getString(k))) } catch (e: Exception) { d }
    val d = DefaultPal
    return Pal(c("primary", d.primary), c("secondary", d.secondary), c("bg1", d.bg1), c("bg2", d.bg2),
        c("card", d.card), c("text", d.text), c("muted", d.muted))
}

fun openLink(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    val u = if (url.startsWith("/")) Prefs.panelUrl + url else url
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

@Composable
fun NetImage(url: String, modifier: Modifier, fallback: Int? = null, scale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<Bitmap?>(null, url) { value = withContext(Dispatchers.IO) { loadBitmap(url) } }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = scale)
    else if (fallback != null) Image(painterResource(fallback), null, modifier, contentScale = scale)
}

@Composable
fun Root(onToggle: () -> Unit) {
    var cfg by remember { mutableStateOf(runCatching { JSONObject(Prefs.cfgJson) }.getOrNull()) }
    var screen by remember { mutableStateOf("home") }
    val pal = remember(cfg) { parsePal(cfg?.optJSONObject("theme")) }

    LaunchedEffect(Unit) {
        val text = withContext(Dispatchers.IO) { try { Api.request("/api/app-config") } catch (e: Exception) { null } }
        if (text != null) { Prefs.cfgJson = text; cfg = runCatching { JSONObject(text) }.getOrNull() ?: cfg }
    }

    CompositionLocalProvider(LocalPal provides pal, LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = pal.primary, secondary = pal.secondary,
            background = pal.bg1, surface = pal.card, onSurface = pal.text, onBackground = pal.text)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(pal.bg1, pal.bg2, pal.bg1)))) {
                if (screen == "home") Home(cfg, onToggle) { screen = "settings" }
                else SettingsScreen { screen = "home" }
            }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(pal.card.copy(alpha = .85f))
        .border(1.dp, pal.primary.copy(alpha = .25f), RoundedCornerShape(18.dp)).padding(14.dp), content = content)
}

@Composable
fun Home(cfg: JSONObject?, onToggle: () -> Unit, onSettings: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val status by VodiState.status.collectAsState()
    val message by VodiState.message.collectAsState()
    val brand = cfg?.optString("brand").orEmpty().ifBlank { "Vodi Panel" }
    val announcement = cfg?.optString("announcement").orEmpty()
    val support = cfg?.optJSONObject("support")
    val latest = cfg?.optJSONObject("latest")
    val banners = cfg?.optJSONArray("banners")

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetImage(cfg?.optJSONObject("logo")?.optString("url").orEmpty(), Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)), R.drawable.logo)
            Spacer(Modifier.width(10.dp))
            Text(brand, color = pal.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("⚙ تنظیمات", color = pal.muted) }
        }

        if (latest != null && latest.optInt("version_code") > BuildConfig.APP_VERSION_CODE) {
            Card(Modifier.clickable { openLink(ctx, latest.optString("url")) }) {
                Text("🚀 نسخه جدید ${latest.optString("version_name")} آماده است", color = pal.primary, fontWeight = FontWeight.Bold)
                Text("برای دانلود لمس کنید", color = pal.muted, fontSize = 13.sp)
            }
        }
        if (announcement.isNotBlank()) Card { Text("📢 $announcement", color = pal.text) }

        // connect button
        val pulse = rememberInfiniteTransition(label = "p")
        val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        val ring = when (status) {
            Status.ON -> Color(0xFF22C55E); Status.CONNECTING -> Color(0xFFF59E0B)
            Status.ERROR -> Color(0xFFEF4444); else -> pal.primary
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(190.dp).alpha(if (status == Status.CONNECTING) a else 1f).clip(CircleShape)
                .background(Brush.radialGradient(listOf(ring.copy(alpha = .35f), Color.Transparent)))
                .border(3.dp, ring, CircleShape).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Text(when (status) { Status.ON -> "قطع"; Status.CONNECTING -> "…"; else -> "اتصال" },
                    color = pal.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(12.dp))
            Text(when (status) {
                Status.ON -> "✅ متصل هستید"; Status.CONNECTING -> message.ifBlank { "در حال اتصال…" }
                Status.ERROR -> "❌ " + message.ifBlank { "اتصال ناموفق بود" }; else -> "قطع است"
            }, color = ring, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }

        ExitCard(status)

        if (banners != null) for (i in 0 until banners.length()) {
            val b = banners.getJSONObject(i)
            NetImage(b.optString("url"), Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 180.dp)
                .clip(RoundedCornerShape(16.dp)).clickable { openLink(ctx, b.optString("link")) })
        }

        // support / telegram
        val links = buildList {
            support?.optString("telegram_channel")?.takeIf { it.isNotBlank() }?.let { add("📣 کانال تلگرام" to it) }
            support?.optString("telegram_support")?.takeIf { it.isNotBlank() }?.let { add("💬 پشتیبانی" to it) }
            support?.optString("email")?.takeIf { it.isNotBlank() }?.let { add("✉ ایمیل" to "mailto:$it") }
            support?.optString("url")?.takeIf { it.isNotBlank() }?.let {
                add("🔗 " + support?.optString("url_label").orEmpty().ifBlank { "لینک" } to it)
            }
        }
        if (links.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                links.forEach { (label, url) ->
                    OutlinedButton(onClick = { openLink(ctx, url) }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(6.dp)) {
                        Text(label, fontSize = 12.sp, color = pal.text, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun ExitCard(status: Status) {
    val pal = LocalPal.current
    val since by VodiState.since.collectAsState()
    val engine by VodiState.engine.collectAsState()
    val verified by VodiState.exit.collectAsState()
    var info by remember { mutableStateOf<ExitInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var rescan by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(status, rescan, verified) {
        if (status != Status.ON) { info = null; return@LaunchedEffect }
        // the connection check already measured location before "connected": show it instantly
        if (rescan == 0 && verified != null) { info = verified; loading = false; return@LaunchedEffect }
        info = null
        loading = true
        delay(1500)
        info = withContext(Dispatchers.IO) { ExitProbe.fetch() }
        loading = false
    }
    LaunchedEffect(status) { while (status == Status.ON) { now = System.currentTimeMillis(); delay(1000) } }

    if (status != Status.ON) return
    val ltr = TextStyle(textDirection = TextDirection.Ltr)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌍 شما به این کشور متصل هستید", color = pal.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(formatElapsed(now - since), color = pal.primary, fontWeight = FontWeight.Bold, style = ltr)
        }
        Spacer(Modifier.height(8.dp))
        val i = info
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = pal.primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("در حال اسکن آی‌پی و کشور…", color = pal.text)
            }
            i != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(flag(i.cc), fontSize = 36.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(i.country ?: "کشور نامشخص", color = pal.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        i.city?.let { Text(it, color = pal.muted, fontSize = 14.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                InfoRow("آی‌پی", i.ip, ltr = true)
                i.isp?.let { InfoRow("اپراتور", it) }
                InfoRow("زمان پاسخ", "${i.ms} ms", ltr = true)
            }
            else -> Text("دریافت آی‌پی و کشور ممکن نشد. دوباره اسکن کنید.", color = pal.muted, fontSize = 14.sp)
        }
        InfoRow("مسیر اتصال", if (engine.startsWith("warp")) "Cloudflare WARP (پشتیبان رایگان)" else "سرورهای اصلی")
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { rescan++ }, enabled = !loading, shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            Text("🔄 اسکن مجدد", fontSize = 13.sp, color = pal.text)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, ltr: Boolean = false) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = pal.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            style = if (ltr) TextStyle(textDirection = TextDirection.Ltr) else TextStyle.Default)
    }
}

fun formatElapsed(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val pal = LocalPal.current
    var token by remember { mutableStateOf(Prefs.subToken) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("→ بازگشت", color = pal.muted) }
            Text("تنظیمات", color = pal.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Card {
            Text("کد اشتراک (اختیاری)", color = pal.text, fontWeight = FontWeight.Bold)
            Text("اگر از فروشنده کد اشتراک گرفته‌اید اینجا وارد کنید. در غیر این‌صورت خالی بگذارید.", color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(token, { token = it; saved = false }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = { Prefs.subToken = token; saved = true }) { Text("ذخیره") }
            if (saved) Text("ذخیره شد ✓ (در اتصال بعدی اعمال می‌شود)", color = pal.primary, fontSize = 13.sp)
        }
        var warp by remember { mutableStateOf(Prefs.warpFallback) }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("پشتیبان رایگان Cloudflare WARP", color = pal.text, fontWeight = FontWeight.Bold)
                    Text("اگر سرورهای اصلی در دسترس نبودند، به‌صورت خودکار از WARP استفاده می‌شود.", color = pal.muted, fontSize = 13.sp)
                }
                Switch(checked = warp, onCheckedChange = { warp = it; Prefs.warpFallback = it })
            }
        }
        Text("Vodi Panel v${BuildConfig.VERSION_NAME}", color = pal.muted, fontSize = 12.sp)
    }
}
