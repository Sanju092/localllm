package com.localllm.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localllm.server.MainActivity
import com.localllm.server.R
import com.localllm.server.data.inference.LlamaEngine
import com.localllm.server.data.repository.LogLevel
import com.localllm.server.data.repository.LogRepository
import com.localllm.server.data.repository.ModelRepository
import com.localllm.server.data.repository.SettingsRepository
import com.localllm.server.server.ServerManager
import com.localllm.server.server.ServerStateHolder
import com.localllm.server.server.ServerStatus
import com.localllm.server.util.NetworkUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Foreground service that keeps the model loaded and the HTTP server alive while the app is in
 * the background or the screen is off.
 */
@AndroidEntryPoint
class LlmServerService : android.app.Service() {

    @Inject lateinit var engine: LlamaEngine
    @Inject lateinit var serverManager: ServerManager
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var models: ModelRepository
    @Inject lateinit var logs: LogRepository
    @Inject lateinit var state: ServerStateHolder

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var startJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                goForeground("Starting…")
                val explicitModel = intent?.getStringExtra(EXTRA_MODEL_PATH)
                startJob?.cancel()
                startJob = scope.launch { startServer(explicitModel) }
            }
            ACTION_STOP -> {
                stopEverything()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun startServer(explicitModelPath: String?) {
        val cfg = settings.getConfig()
        val path = explicitModelPath
            ?: cfg.selectedModelFileName?.let { models.fileFor(it) }?.takeIf { it.exists() }?.absolutePath
            ?: models.installed.value.firstOrNull()?.path
        if (path == null) {
            fail("No model installed. Download one from the Models tab.")
            return
        }
        val name = path.substringAfterLast('/').removeSuffix(".gguf")
        state.update { it.copy(status = ServerStatus.LOADING, modelName = name, port = cfg.port, error = null) }
        updateNotification("Loading $name…")
        try {
            serverManager.stop()
            val started = System.currentTimeMillis()
            logs.log("Loading model $name (ctx ${cfg.contextSize}, threads ${if (cfg.threads == 0) "auto" else cfg.threads})")
            val loaded = engine.loadModel(path, cfg.contextSize, cfg.threads)
            logs.log("Model loaded in %.1fs: %s".format((System.currentTimeMillis() - started) / 1000.0, loaded.info.description))
            serverManager.start(cfg)
            acquireLocks()
            state.update {
                it.copy(
                    status = ServerStatus.RUNNING,
                    modelName = loaded.name,
                    contextSize = loaded.info.n_ctx,
                    port = cfg.port,
                    startedAt = System.currentTimeMillis(),
                    error = null,
                )
            }
            val ip = NetworkUtils.lanIpAddress() ?: "0.0.0.0"
            updateNotification("Running • http://$ip:${cfg.port}/v1 • ${loaded.name}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            fail(e.message ?: e.toString())
        }
    }

    private fun fail(message: String) {
        logs.log("Server failed to start: $message", LogLevel.ERROR)
        serverManager.stop()
        state.update { it.copy(status = ServerStatus.ERROR, error = message) }
        updateNotification("Error: $message")
        releaseLocks()
    }

    private fun stopEverything() {
        startJob?.cancel()
        serverManager.stop()
        runBlocking { engine.unload() }
        releaseLocks()
        state.update { it.copy(status = ServerStatus.STOPPED, startedAt = 0, activeRequests = 0, error = null) }
        logs.log("Server stopped, model unloaded")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (state.state.value.status != ServerStatus.STOPPED) stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalLLM::Server")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "LocalLLM::Wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun goForeground(status: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(status), type)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "LLM Server", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when the local LLM server is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LlmServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local LLM Server")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setSmallIcon(R.drawable.ic_server)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_START = "com.localllm.server.START"
        const val ACTION_STOP = "com.localllm.server.STOP"
        const val EXTRA_MODEL_PATH = "model_path"
        private const val NOTIFICATION_ID = 8080
        private const val CHANNEL_ID = "llm_server_channel"

        fun start(context: Context, modelPath: String? = null) {
            val intent = Intent(context, LlmServerService::class.java).setAction(ACTION_START)
            if (modelPath != null) intent.putExtra(EXTRA_MODEL_PATH, modelPath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LlmServerService::class.java).setAction(ACTION_STOP))
        }
    }
}
