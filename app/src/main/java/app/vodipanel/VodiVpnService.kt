package app.vodipanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.*

class VodiVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var active: Engine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { shutdown(); return START_NOT_STICKY }
        startForeground(1, notification())
        job?.cancel()
        job = scope.launch {
            VodiState.status.value = Status.CONNECTING
            VodiState.message.value = ""
            var firstError: String? = null
            for (e in VodiController.engines()) {
                try {
                    val cfg = e.prepare(this@VodiVpnService)
                    LibboxCore.start(this@VodiVpnService, cfg)
                    active = e
                    VodiState.engine.value = e.id
                    VodiState.since.value = System.currentTimeMillis()
                    VodiState.status.value = Status.ON
                    return@launch
                } catch (c: CancellationException) {
                    throw c
                } catch (ex: ApiException) {
                    if (firstError == null) firstError = ex.message
                    // invalid / expired subscription or rate limit: never fall back to the free path
                    if (ex.code == 403 || ex.code == 429) break
                } catch (ex: java.io.IOException) {
                    if (firstError == null) firstError = "ارتباط با سرور برقرار نشد؛ اینترنت را بررسی کنید"
                } catch (ex: Throwable) {
                    android.util.Log.e("Vodi", "start failed", ex)
                    if (firstError == null) firstError = ex.message ?: ex.javaClass.simpleName
                    LibboxCore.stop()
                }
            }
            VodiState.message.value = firstError ?: "اتصال برقرار نشد"
            VodiState.status.value = Status.ERROR
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        job?.cancel()
        runBlocking { active?.stop() }
        LibboxCore.stop()
        VodiState.status.value = Status.OFF
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() = shutdown()

    override fun onDestroy() {
        scope.cancel()
        LibboxCore.stop()
        if (VodiState.status.value != Status.ERROR) VodiState.status.value = Status.OFF
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vodi", "Vodi Panel", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "vodi").setContentTitle("Vodi Panel")
            .setContentText("VPN active").setSmallIcon(android.R.drawable.ic_lock_lock).build()
    }
}
