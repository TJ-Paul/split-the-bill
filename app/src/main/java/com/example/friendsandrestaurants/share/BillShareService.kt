package com.example.friendsandrestaurants.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.friendsandrestaurants.BillRepository
import com.example.friendsandrestaurants.MainActivity
import com.example.friendsandrestaurants.R

/** Whether the bill is being shared on the local network, for the screens to show. */
object ShareSession {
    enum class Status { STOPPED, STARTING, RUNNING, FAILED }

    data class State(val status: Status = Status.STOPPED, val port: Int = 0, val viewers: Int = 0) {
        val isActive: Boolean get() = status == Status.STARTING || status == Status.RUNNING
    }

    private val _state = MutableLiveData(State())
    val state: LiveData<State> = _state

    val current: State get() = _state.value ?: State()

    @MainThread
    internal fun update(state: State) {
        if (_state.value != state) _state.value = state
    }

    @MainThread
    fun start(context: Context) {
        if (!current.isActive) update(State(Status.STARTING))
        try {
            ContextCompat.startForegroundService(context, Intent(context, BillShareService::class.java))
        } catch (e: Exception) {
            // e.g. the system refused to start a foreground service right now.
            update(State(Status.FAILED))
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, BillShareService::class.java))
    }
}

/**
 * Runs the guest web page while the bill is shared. It is a foreground service so the page keeps
 * working when the host's screen turns off or they switch apps; the notification can stop it.
 */
class BillShareService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val viewers = ViewerTracker()
    private var server: LocalWebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val viewerTicker = object : Runnable {
        override fun run() {
            val count = viewers.count()
            val state = ShareSession.current
            if (state.status == ShareSession.Status.RUNNING && state.viewers != count) {
                ShareSession.update(state.copy(viewers = count))
                notifyForeground()
            }
            handler.postDelayed(this, VIEWER_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go foreground first: the system requires it soon after startForegroundService().
        goForeground()
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) startServer()
        return START_NOT_STICKY
    }

    private fun startServer() {
        ShareSession.update(ShareSession.State(ShareSession.Status.STARTING))
        acquireWakeLock()

        val api = GuestApi(BillRepository.get(application), viewers) {
            assets.open(PAGE_ASSET).use { it.readBytes() }
        }
        val newServer = LocalWebServer(api)
        server = newServer

        // Binding a socket is network work, so keep it off the main thread.
        Thread({
            val result = runCatching { newServer.start(PREFERRED_PORTS) }
            handler.post {
                if (server !== newServer) {
                    // Stopped while starting.
                    Thread { newServer.stop() }.start()
                    return@post
                }
                result.onSuccess { port ->
                    ShareSession.update(ShareSession.State(ShareSession.Status.RUNNING, port = port))
                    handler.removeCallbacks(viewerTicker)
                    handler.post(viewerTicker)
                    notifyForeground()
                }.onFailure {
                    ShareSession.update(ShareSession.State(ShareSession.Status.FAILED))
                    stopSelf()
                }
            }
        }, "bill-share-start").start()
    }

    override fun onDestroy() {
        handler.removeCallbacks(viewerTicker)
        server?.let { s -> Thread { s.stop() }.start() }
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (ShareSession.current.status != ShareSession.Status.FAILED) {
            ShareSession.update(ShareSession.State())
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java) ?: return
        // Keeps the CPU awake so friends can still add orders while the host's screen is off.
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SplitTheBill:share").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyForeground() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // Without notification permission this is a no-op, and the service keeps running.
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): android.app.Notification {
        createChannel()
        val open = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 1, Intent(this, BillShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val state = ShareSession.current
        val text = if (state.status == ShareSession.Status.RUNNING && state.viewers > 0) {
            resources.getQuantityString(R.plurals.share_notification_viewers, state.viewers, state.viewers)
        } else {
            getString(R.string.share_notification_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qr_code)
            .setContentTitle(getString(R.string.share_notification_title))
            .setContentText(text)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_close, getString(R.string.share_stop), stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.share_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.share_channel_description) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.example.friendsandrestaurants.share.STOP"
        private const val CHANNEL_ID = "bill_share"
        private const val NOTIFICATION_ID = 41
        private const val PAGE_ASSET = "guest/index.html"
        private const val VIEWER_REFRESH_MS = 3_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        /** A fixed port keeps the page address (and guests' saved access) the same between sessions. */
        private val PREFERRED_PORTS = listOf(8080, 8081, 8082, 8088, 8090, 8888)
    }
}
