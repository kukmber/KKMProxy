package io.github.romanvht.byedpi.telegram

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.DashboardActivity
import io.github.romanvht.byedpi.telegram.proxy.ProxyConfig
import io.github.romanvht.byedpi.telegram.proxy.ProxyPortInUseException
import io.github.romanvht.byedpi.telegram.proxy.TgWsProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TelegramProxyStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

data class TelegramProxyState(
    val status: TelegramProxyStatus = TelegramProxyStatus.STOPPED,
    val error: String? = null,
    val portInUse: Boolean = false,
)

class TelegramProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var server: TgWsProxyServer? = null
    private var serverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_title_tgws),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            return START_NOT_STICKY
        }
        startProxy()
        return START_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) {
            server?.stop()
            server = null
            serverJob?.cancel()
            serverJob = null
        }
        scope.cancel()
        if (_state.value.status != TelegramProxyStatus.FAILED) {
            _state.value = TelegramProxyState()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        synchronized(lock) {
            if (serverJob?.isActive == true) return
            val config = config(this)
            val newServer = TgWsProxyServer(config).apply {
                onStatusChange = { listening ->
                    if (listening) _state.value = TelegramProxyState(TelegramProxyStatus.RUNNING)
                }
            }
            TgWsProxyServer.globalVerbose = BuildConfig.DEBUG
            server = newServer
            _state.value = TelegramProxyState(TelegramProxyStatus.STARTING)
            serverJob = scope.launch {
                try {
                    newServer.start()
                    if (_state.value.status != TelegramProxyStatus.STOPPING) {
                        _state.value = TelegramProxyState()
                    }
                } catch (error: ProxyPortInUseException) {
                    Log.e(TAG, "Telegram proxy port ${error.port} is already in use", error)
                    _state.value = TelegramProxyState(
                        status = TelegramProxyStatus.FAILED,
                        error = error.message,
                        portInUse = true,
                    )
                    stopSelf()
                } catch (error: Exception) {
                    Log.e(TAG, "Telegram proxy failed", error)
                    _state.value = TelegramProxyState(
                        status = TelegramProxyStatus.FAILED,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                    stopSelf()
                } finally {
                    synchronized(lock) {
                        if (server === newServer) server = null
                    }
                }
            }
        }
    }

    private fun stopProxy() {
        _state.value = TelegramProxyState(TelegramProxyStatus.STOPPING)
        scope.launch {
            val activeServer = synchronized(lock) { server }
            activeServer?.stop()
            synchronized(lock) {
                serverJob?.cancel()
                serverJob = null
                server = null
            }
            _state.value = TelegramProxyState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground() {
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, TelegramProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title_tgws))
            .setContentText("MTProto · 127.0.0.1:$DEFAULT_PORT")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.service_stop_btn), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "TelegramProxyService"
        private const val PREFS_NAME = "telegram_mtproto_proxy"
        private const val SECRET_KEY = "secret"
        private const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443
        private const val ACTION_START = "io.github.romanvht.byedpi.telegram.START"
        private const val ACTION_STOP = "io.github.romanvht.byedpi.telegram.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "telegram_mtproto_proxy"
        private const val NOTIFICATION_ID = 14430
        private const val CONTENT_REQUEST_CODE = 14431
        private const val STOP_REQUEST_CODE = 14432

        private val _state = MutableStateFlow(TelegramProxyState())
        val state: StateFlow<TelegramProxyState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TelegramProxyService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TelegramProxyService::class.java).setAction(ACTION_STOP),
            )
        }

        fun secret(context: Context): String = synchronized(this) {
            val preferences = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            preferences.getString(SECRET_KEY, null)
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }
                ?.lowercase()
                ?: ProxyConfig.generateSecret().also {
                    preferences.edit().putString(SECRET_KEY, it).commit()
                }
        }

        fun config(context: Context): ProxyConfig = ProxyConfig(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            secret = secret(context),
            cfProxyEnabled = true,
            cfProxyPriority = true,
            cfProxyFirst = false,
        )

        fun proxyLink(context: Context): String = config(context).proxyLink()
    }
}
