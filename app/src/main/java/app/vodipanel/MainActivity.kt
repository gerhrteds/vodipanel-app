package app.vodipanel

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        setContent { Root(onToggle = ::toggle, onConnectNow = ::connectNow) }
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

    /** Called after the customer picked a country: connect now (or reconnect if a tunnel is already up). */
    private fun connectNow() {
        val i = VpnService.prepare(this)
        if (i != null) vpnPermission.launch(i) else startVpn()
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
fun Root(onToggle: () -> Unit, onConnectNow: () -> Unit) {
    var cfg by remember { mutableStateOf(runCatching { JSONObject(Prefs.cfgJson) }.getOrNull()) }
    var screen by remember { mutableStateOf("home") }
    var selCc by remember { mutableStateOf(Prefs.selectedCc) }
    var selName by remember { mutableStateOf(Prefs.selectedName) }
    val pal = remember(cfg) { parsePal(cfg?.optJSONObject("theme")) }

    LaunchedEffect(Unit) {
        val text = withContext(Dispatchers.IO) { try { Api.request("/api/app-config") } catch (e: Exception) { null } }
        if (text != null) { Prefs.cfgJson = text; cfg = runCatching { JSONObject(text) }.getOrNull() ?: cfg }
    }
    BackHandler(enabled = screen != "home") { screen = "home" }

    fun pick(cc: String, name: String) {
        val changed = cc != Prefs.selectedCc
        Prefs.selectedCc = cc; Prefs.selectedName = name
        selCc = cc; selName = name
        screen = "home"
        val st = VodiState.status.value
        // connect right away; when already connected only reconnect if the country really changed
        if (st == Status.OFF || st == Status.ERROR || changed) onConnectNow()
    }

    CompositionLocalProvider(LocalPal provides pal, LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = darkColorScheme(primary = pal.primary, secondary = pal.secondary,
            background = pal.bg1, surface = pal.card, onSurface = pal.text, onBackground = pal.text)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(pal.bg1, pal.bg2, pal.bg1)))) {
                when (screen) {
                    "settings" -> SettingsScreen(cfg, selCc, selName, onBack = { screen = "home" },
                        onOpen = { screen = it }, onAuto = { pick("", "") })
                    "support" -> SupportScreen(cfg) { screen = "home" }
                    "locations" -> LocationsScreen(selCc, onBack = { screen = "home" }, onPick = ::pick)
                    else -> Home(cfg, selCc, selName, onToggle, onOpen = { screen = it })
                }
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
fun ScreenHeader(title: String, onBack: () -> Unit) {
    val pal = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("→ بازگشت", color = pal.muted) }
        Text(title, color = pal.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** True when the "join our channel" dialog should be shown now. */
fun shouldAskChannel(cfg: JSONObject?): Boolean {
    val sp = cfg?.optJSONObject("support") ?: return false
    if (!sp.optBoolean("channel_prompt", false) || sp.optString("telegram_channel").isBlank()) return false
    return !Prefs.chanJoined && System.currentTimeMillis() >= Prefs.chanSnoozeUntil
}

@Composable
fun ChannelDialog(cfg: JSONObject?, onClose: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val sp = cfg?.optJSONObject("support")
    val text = sp?.optString("channel_prompt_text").orEmpty()
        .ifBlank { "برای اطلاع از آپدیت‌ها و سرورهای جدید عضو کانال تلگرام ما شوید." }
    val hours = (sp?.optInt("channel_prompt_hours", 24) ?: 24).coerceIn(1, 720)
    val later = { Prefs.chanSnoozeUntil = System.currentTimeMillis() + hours * 3_600_000L; onClose() }
    Dialog(onDismissRequest = later) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(pal.card)
            .border(1.dp, pal.primary.copy(alpha = .5f), RoundedCornerShape(24.dp)).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📣", fontSize = 46.sp)
            Spacer(Modifier.height(6.dp))
            Text("عضو کانال ما شوید", color = pal.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(text, color = pal.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                openLink(ctx, sp?.optString("telegram_channel").orEmpty())
                Prefs.chanJoined = true
                onClose()
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pal.primary, contentColor = Color.White)) {
                Text("📣 عضویت در کانال", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = later) { Text("بعداً", color = pal.muted) }
        }
    }
}

@Composable
fun Home(cfg: JSONObject?, selCc: String, selName: String, onToggle: () -> Unit, onOpen: (String) -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val status by VodiState.status.collectAsState()
    val message by VodiState.message.collectAsState()
    val brand = cfg?.optString("brand").orEmpty().ifBlank { "Vodi Panel" }
    val announcement = cfg?.optString("announcement").orEmpty()
    val latest = cfg?.optJSONObject("latest")
    val banners = cfg?.optJSONArray("banners")

    // "join our channel" dialog: shown when the app opens, the customer may press "later"
    var showChan by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(cfg) { if (!asked && shouldAskChannel(cfg)) { asked = true; showChan = true } }
    if (showChan) ChannelDialog(cfg) { showChan = false }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetImage(cfg?.optJSONObject("logo")?.optString("url").orEmpty(), Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)), R.drawable.logo)
            Spacer(Modifier.width(10.dp))
            Text(brand, color = pal.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onOpen("settings") }) { Text("⚙ تنظیمات", color = pal.muted) }
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

        // chosen location: tap to open the country list (with live ping) and connect to another country
        Card(Modifier.clickable { onOpen("locations") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (selCc.length == 2) flag(selCc) else "🌐", fontSize = 32.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("موقعیت اتصال", color = pal.muted, fontSize = 12.sp)
                    Text(if (selCc.length == 2) selName.ifBlank { selCc } else "خودکار (بهترین سرور)",
                        color = pal.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text("تغییر ‹", color = pal.primary, fontWeight = FontWeight.Bold)
            }
        }

        ExitCard(status)

        if (banners != null) for (i in 0 until banners.length()) {
            val b = banners.getJSONObject(i)
            NetImage(b.optString("url"), Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 180.dp)
                .clip(RoundedCornerShape(16.dp)).clickable { openLink(ctx, b.optString("link")) })
        }

        // support + channel
        Button(onClick = { onOpen("support") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = pal.primary.copy(alpha = .22f), contentColor = pal.text)) {
            Text("💬 پشتیبانی و عضویت در کانال", fontWeight = FontWeight.Bold)
        }
    }
}

// ------------------------------------------------------------------------------------------ locations (countries + live ping)

fun pingColor(ms: Int): Color = when {
    ms <= 0 -> Color(0xFFEF4444)
    ms <= 150 -> Color(0xFF22C55E)
    ms <= 350 -> Color(0xFFF59E0B)
    else -> Color(0xFFEF4444)
}

@Composable
fun LocationsScreen(selCc: String, onBack: () -> Unit, onPick: (String, String) -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var locs by remember { mutableStateOf<List<Loc>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var measuring by remember { mutableStateOf(false) }
    val pings = remember { mutableStateMapOf<String, PingR>() }

    LaunchedEffect(reload) {
        locs = null; error = null; pings.clear()
        val res = withContext(Dispatchers.IO) { runCatching { Locations.fetch() } }
        val list = res.getOrNull()
        if (list == null) {
            val ex = res.exceptionOrNull()
            error = if (ex is ApiException) ex.message else "ارتباط با سرور برقرار نشد؛ اینترنت را بررسی کنید"
            return@LaunchedEffect
        }
        locs = list
        measuring = true
        // real ping from this phone, a few countries at a time
        val sem = Semaphore(6)
        coroutineScope {
            for (l in list) launch {
                sem.withPermit {
                    val r = withContext(Dispatchers.IO) { Locations.measure(ctx, l) }
                    pings[l.cc] = r
                }
            }
        }
        measuring = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenHeader("انتخاب کشور", onBack) }
            TextButton(onClick = { reload++ }, enabled = !measuring) { Text("🔄 پینگ مجدد", color = pal.primary) }
        }
        Text("پینگ از روی گوشی شما اندازه‌گیری می‌شود. روی کشور مورد نظر بزنید تا متصل شوید.", color = pal.muted, fontSize = 13.sp)

        val list = locs
        when {
            error != null -> Card {
                Text("❌ " + error, color = pal.text)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { reload++ }, shape = RoundedCornerShape(12.dp)) { Text("تلاش دوباره", color = pal.text) }
            }
            list == null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = pal.primary)
            }
            else -> {
                // order: by measured ping once every country answered, before that in the panel's order
                val sorted = if (measuring) list else list.sortedBy {
                    val r = pings[it.cc]
                    if (r == null || r.ms <= 0) Int.MAX_VALUE else r.ms
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocRow("🌐", "خودکار (بهترین سرور)", "اتصال هوشمند به سریع‌ترین سرور", null, selCc.isEmpty()) { onPick("", "") }
                    if (list.isEmpty()) Card { Text("فعلاً کشوری برای انتخاب در دسترس نیست؛ حالت «خودکار» را بزنید.", color = pal.muted) }
                    for (l in sorted) LocRow(flag(l.cc), l.name, "${l.count} سرور", pings[l.cc], selCc.equals(l.cc, true)) { onPick(l.cc, l.name) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun LocRow(icon: String, title: String, sub: String, ping: PingR?, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(pal.card.copy(alpha = .85f))
        .border(if (selected) 2.dp else 1.dp, if (selected) pal.primary else pal.primary.copy(alpha = .2f), shape)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 30.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(sub, color = pal.muted, fontSize = 12.sp)
        }
        if (selected) Text("✓", color = pal.primary, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        val ltr = TextStyle(textDirection = TextDirection.Ltr)
        when {
            ping == null && icon != "🌐" -> CircularProgressIndicator(Modifier.size(16.dp), color = pal.muted, strokeWidth = 2.dp)
            ping != null -> {
                val c = pingColor(ping.ms)
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = .16f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(if (ping.ms <= 0) "بدون پاسخ" else (if (ping.est) "~" else "") + "${ping.ms} ms",
                        color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp, style = if (ping.ms > 0) ltr else TextStyle.Default)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ support + channel

@Composable
fun SupportScreen(cfg: JSONObject?, onBack: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val support = cfg?.optJSONObject("support")
    fun link(k: String) = support?.optString(k).orEmpty()
    val channel = link("telegram_channel"); val tg = link("telegram_support")
    val email = link("email"); val url = link("url")
    var contact by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("پشتیبانی و کانال", onBack)

        if (channel.isNotBlank()) Card {
            Text("📣 کانال تلگرام", color = pal.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("آپدیت‌ها، سرورهای جدید و اطلاعیه‌ها را اول از همه در کانال ببینید.", color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { Prefs.chanJoined = true; openLink(ctx, channel) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = pal.primary, contentColor = Color.White)) {
                Text("📣 عضویت در کانال", fontWeight = FontWeight.Bold)
            }
        }

        if (tg.isNotBlank() || email.isNotBlank() || url.isNotBlank()) Card {
            Text("💬 ارتباط با پشتیبان", color = pal.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tg.isNotBlank()) OutlinedButton(onClick = { openLink(ctx, tg) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("💬 پشتیبانی در تلگرام", color = pal.text) }
                if (email.isNotBlank()) OutlinedButton(onClick = { openLink(ctx, "mailto:$email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("✉ ایمیل پشتیبانی", color = pal.text) }
                if (url.isNotBlank()) OutlinedButton(onClick = { openLink(ctx, url) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("🔗 " + link("url_label").ifBlank { "لینک پشتیبانی" }, color = pal.text) }
            }
        }

        Card {
            Text("✉ ارسال پیام به پشتیبانی", color = pal.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("مشکلتان را بنویسید؛ پیام مستقیم به پنل مدیریت می‌رسد.", color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(contact, { contact = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("آیدی تلگرام یا ایمیل (برای پاسخ)") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(text, { text = it.take(2000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("پیام شما") })
            Spacer(Modifier.height(8.dp))
            Button(enabled = !sending && text.isNotBlank(), onClick = {
                scope.launch {
                    sending = true
                    result = withContext(Dispatchers.IO) {
                        try {
                            val body = JSONObject().put("name", "اپ اندروید").put("contact", contact).put("text", text).toString()
                            Api.fetch(Prefs.panelUrl + "/api/support/message", body, mapOf("Content-Type" to "application/json"))
                            "✅ پیام شما ارسال شد"
                        } catch (e: ApiException) {
                            if (e.code == 429) "تعداد پیام‌ها زیاد است؛ بعداً تلاش کنید" else "ارسال نشد؛ دوباره تلاش کنید"
                        } catch (e: Exception) {
                            "ارتباط با سرور برقرار نشد"
                        }
                    }
                    if (result.startsWith("✅")) text = ""
                    sending = false
                }
            }) { Text(if (sending) "در حال ارسال…" else "ارسال پیام") }
            if (result.isNotBlank()) Text(result, color = pal.primary, fontSize = 13.sp)
        }

        Card {
            Text("💡 اگر وصل نمی‌شوید", color = pal.text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("• چند ثانیه صبر کنید و دوباره دکمه اتصال را بزنید.\n• از «انتخاب کشور» یک کشور دیگر (با پینگ کمتر) را امتحان کنید.\n• حالت «خودکار» را انتخاب کنید.\n• اگر کد اشتراک دارید، در تنظیمات درست وارد کرده باشید.",
                color = pal.muted, fontSize = 13.sp)
        }
    }
}

// ------------------------------------------------------------------------------------------ settings

/** Asks the panel whether the subscription code (or public mode) is accepted. Blocking: run on Dispatchers.IO. */
fun validateToken(token: String): String {
    return try {
        Locations.fetch()
        if (token.isBlank()) "ذخیره شد ✓" else "✅ کد اشتراک معتبر است و ذخیره شد"
    } catch (e: ApiException) {
        "❌ " + (e.message ?: "کد اشتراک نامعتبر است")
    } catch (e: Exception) {
        "ذخیره شد ✓ (اعتبار کد به‌خاطر قطعی شبکه بررسی نشد)"
    }
}

@Composable
fun SettingsScreen(cfg: JSONObject?, selCc: String, selName: String, onBack: () -> Unit, onOpen: (String) -> Unit, onAuto: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(Prefs.subToken) }
    var tokenMsg by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var warp by remember { mutableStateOf(Prefs.warpFallback) }
    var updMsg by remember { mutableStateOf("") }
    var updNew by remember { mutableStateOf(false) }
    val brand = cfg?.optString("brand").orEmpty().ifBlank { "Vodi Panel" }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("تنظیمات", onBack)

        Card {
            Text("🌍 موقعیت اتصال", color = pal.text, fontWeight = FontWeight.Bold)
            Text(if (selCc.length == 2) "کشور انتخابی: ${flag(selCc)} ${selName.ifBlank { selCc }}" else "حالت فعلی: خودکار (بهترین سرور)",
                color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen("locations") }, shape = RoundedCornerShape(12.dp)) { Text("انتخاب کشور") }
                if (selCc.length == 2) OutlinedButton(onClick = onAuto, shape = RoundedCornerShape(12.dp)) { Text("حالت خودکار", color = pal.text) }
            }
        }

        Card {
            Text("🔑 کد اشتراک (اختیاری)", color = pal.text, fontWeight = FontWeight.Bold)
            Text("اگر از فروشنده کد اشتراک گرفته‌اید اینجا وارد کنید. در غیر این‌صورت خالی بگذارید.", color = pal.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(token, { token = it; tokenMsg = "" }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(enabled = !checking, onClick = {
                Prefs.subToken = token
                scope.launch {
                    checking = true
                    tokenMsg = withContext(Dispatchers.IO) { validateToken(token) }
                    checking = false
                }
            }) { Text(if (checking) "در حال بررسی…" else "ذخیره و بررسی") }
            if (tokenMsg.isNotBlank()) Text(tokenMsg, color = pal.primary, fontSize = 13.sp)
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🛟 پشتیبان رایگان Cloudflare WARP", color = pal.text, fontWeight = FontWeight.Bold)
                    Text("اگر سرورهای اصلی در دسترس نبودند، به‌صورت خودکار از WARP استفاده می‌شود. وقتی خودتان یک کشور انتخاب کرده‌اید، WARP استفاده نمی‌شود تا ناخواسته کشورتان عوض نشود.",
                        color = pal.muted, fontSize = 13.sp)
                }
                Switch(checked = warp, onCheckedChange = { warp = it; Prefs.warpFallback = it })
            }
        }

        Card(Modifier.clickable { onOpen("support") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("💬 پشتیبانی و عضویت در کانال", color = pal.text, fontWeight = FontWeight.Bold)
                    Text("کانال تلگرام، پشتیبان و ارسال پیام", color = pal.muted, fontSize = 13.sp)
                }
                Text("‹", color = pal.primary, fontSize = 22.sp)
            }
        }

        Card {
            Text("🚀 بروزرسانی", color = pal.text, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    scope.launch {
                        updMsg = "در حال بررسی…"; updNew = false
                        val t = withContext(Dispatchers.IO) { runCatching { Api.request("/api/app-config") }.getOrNull() }
                        val l = t?.let { runCatching { JSONObject(it).optJSONObject("latest") }.getOrNull() }
                        updMsg = when {
                            t == null -> "ارتباط با سرور برقرار نشد"
                            l != null && l.optInt("version_code") > BuildConfig.APP_VERSION_CODE -> { updNew = true; "🚀 نسخه ${l.optString("version_name")} آماده است" }
                            else -> "✅ آخرین نسخه را دارید"
                        }
                    }
                }, shape = RoundedCornerShape(12.dp)) { Text("بررسی نسخه جدید", color = pal.text) }
                if (updNew) Button(onClick = { openLink(ctx, "/download") }, shape = RoundedCornerShape(12.dp)) { Text("دانلود") }
            }
            if (updMsg.isNotBlank()) Text(updMsg, color = pal.primary, fontSize = 13.sp)
        }

        Card {
            Text("ℹ️ درباره", color = pal.text, fontWeight = FontWeight.Bold)
            Text("$brand · نسخه ${BuildConfig.VERSION_NAME}", color = pal.muted, fontSize = 13.sp)
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
