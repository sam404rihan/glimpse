package app.glimpse.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.glimpse.R
import app.glimpse.GlimpseApp
import app.glimpse.android.MODEL_VERSION
import app.glimpse.android.MediaStoreSource
import app.glimpse.android.PcClient
import app.glimpse.android.PcFaceAnalyzer
import app.glimpse.android.PcLink
import app.glimpse.android.PhoneFaceAnalyzer
import app.glimpse.index.FaceAnalyzer
import app.glimpse.index.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val rate: Double = 0.0, // photos per second
    val message: String = "",
    val onPc: Boolean = false, // faces are being found on the paired PC
)

/** Runs one scan at a time, whoever asks (app open, gallery change, charging job). */
class ScanController(private val app: GlimpseApp) {
    private val mutex = Mutex()
    @Volatile private var stopRequested = false
    @Volatile private var pc: PcFaceAnalyzer? = null
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun stop() { stopRequested = true }

    /** Returns false if a scan was already running (that one picks up the changes). */
    suspend fun scan(): Boolean {
        if (mutex.isLocked) return false
        return mutex.withLock {
            stopRequested = false
            withContext(Dispatchers.Default) { runScan() }
            true
        }
    }

    private fun runScan() {
        val t0 = System.nanoTime()
        _state.value = ScanState(running = true, phase = "Looking for new photos")
        pc = null
        try {
            val scanner = Scanner(MediaStoreSource(app), app.store, MODEL_VERSION, ::analyzer)
            val r = scanner.scan(stop = { stopRequested }) { p ->
                val secs = (System.nanoTime() - t0) / 1e9
                _state.value = if (p.phase == Scanner.Phase.LISTING) ScanState(true, "Looking for new photos")
                else ScanState(true, "Finding faces", p.done, p.total, p.done / maxOf(secs, 1e-3), onPc = pc?.pcDown == false)
                // show pictures as soon as listing is done, then new people every 10 batches
                if (p.phase == Scanner.Phase.ANALYSING && (p.done <= Scanner.BATCH || p.done % 160 == 0)) changes.tryEmit(Unit)
            }
            if (r.facesChanged) {
                _state.value = _state.value.copy(phase = "Grouping faces")
                app.people.recluster()
            }
            val secs = (System.nanoTime() - t0) / 1e9
            val msg = when {
                r.stopped -> "Stopped. ${r.analysed} photos checked; the rest continue next time."
                r.analysed > 0 -> "Checked ${r.analysed} new photos in %.0f s".format(secs) +
                    (if (r.failed > 0) " (${r.failed} unreadable)" else "")
                else -> ""
            }
            _state.value = ScanState(message = msg)
        } catch (e: Exception) {
            _state.value = ScanState(message = "Scan failed: ${e.message ?: e.javaClass.simpleName}")
        }
        changes.tryEmit(Unit)
    }

    /** The paired PC if it answers right now, else the phone itself. */
    private fun analyzer(): FaceAnalyzer {
        val client = PcLink.load(app)?.let(::PcClient)?.takeIf { runCatching { it.hello() }.isSuccess }
        return client?.let { PcFaceAnalyzer(app, it).also { a -> pc = a } } ?: PhoneFaceAnalyzer(app)
    }

    /** Rescans shortly after the gallery changes, while the app is open. */
    fun observe(onChange: () -> Unit): ContentObserver {
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable(onChange)
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, 3000)
            }
        }
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        return obs
    }
}

/** Foreground service so a long first scan survives the screen turning off. */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as GlimpseApp
        if (intent?.action == ACTION_STOP) { app.scans.stop(); return START_NOT_STICKY }
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val shown = app.scans.state.value.takeIf { it.running } ?: ScanState(running = true, phase = "Looking for new photos")
        ServiceCompat.startForeground(this, NOTE_ID, note(shown), type)
        // Another start while our scan runs must not stop the service: the scan would go on unprotected.
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            val watch = launch { app.scans.state.collect { s -> if (s.running) notify(s) } }
            app.scans.scan()
            watch.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private var lastNote = 0L
    private fun notify(s: ScanState) {
        val now = System.currentTimeMillis()
        if (now - lastNote < 1000) return
        lastNote = now
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(s))
    }

    private fun note(s: ScanState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scanning photos", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(s.phase.ifEmpty { "Glimpse" })
            .setContentText(if (s.total > 0) "${s.done} of ${s.total} photos" + (if (s.onPc) " · on your PC" else "") else "On this phone")
            .setProgress(s.total, s.done, s.total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "scan"
        private const val NOTE_ID = 1
        private const val ACTION_STOP = "app.glimpse.STOP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ScanService::class.java)) }
        }
    }
}

/** Picks up new photos while the phone charges and sits idle. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as GlimpseApp).scans.scan()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ScanWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
                .build()
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("scan") // registered by builds before the rename, under a class name that no longer exists
            wm.enqueueUniquePeriodicWork("glimpse-scan", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
