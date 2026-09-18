package io.github.romanvht.byedpi.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.DashboardActivity
import io.github.romanvht.byedpi.core.TProxyService
import io.github.romanvht.byedpi.utility.registerNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

enum class VpnStatus { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

/**
 * VPN на ядре mihomo: mihomo работает отдельным процессом (libmihomo.so) и
 * слушает SOCKS на 127.0.0.1, а TUN-трафик в него отправляет hev-socks5-tunnel.
 * DNS разбирает сам hev (mapdns): приложения получают подменные адреса, а в
 * mihomo уходят доменные имена — их резолвит уже сервер VPN.
 * Само приложение исключено из VPN, поэтому соединения mihomo идут напрямую.
 */
class KkmVpnService : VpnService() {

    companion object {
        private const val TAG = "KkmVpnService"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_NODE_NAME = "node_name"
        private const val ACTION_STOP = "io.github.romanvht.byedpi.vpn.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "kkm_vpn"
        private const val NOTIFICATION_ID = 14500
        private const val START_TIMEOUT_MS = 30_000L

        const val LOG_FILE = "mihomo/mihomo.log"

        private const val MAPDNS_ADDRESS = "198.18.0.2"
        private const val MAPDNS_NETWORK = "198.18.0.0"
        private const val MAPDNS_NETMASK = "255.254.0.0"
        private const val TUN_MTU = 8500
        private const val HEALTH_TIMEOUT_MS = 10_000
        private const val HEALTH_FAILURES = 3
        private const val HEALTH_INTERVAL_MS = 30_000L

        private val _state = MutableStateFlow(VpnStatus.STOPPED)
        val state: StateFlow<VpnStatus> = _state.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        /** Порты работающего ядра (для API контроллера), null если VPN выключен */
        @Volatile
        var corePorts: VpnConfigParser.CorePorts? = null
            private set

        @Volatile
        var activeNodeName: String? = null
            private set

        fun start(context: Context, profileId: String, nodeName: String) {
            val intent = Intent(context, KkmVpnService::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_NODE_NAME, nodeName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            if (_state.value == VpnStatus.STOPPED || _state.value == VpnStatus.FAILED) return
            val intent = Intent(context, KkmVpnService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        val isActive: Boolean
            get() = _state.value == VpnStatus.RUNNING || _state.value == VpnStatus.STARTING
    }

    private var coreProcess: Process? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var tunnelStarted = false

    @Volatile
    private var stopping = false

    // Все запуски/остановки строго последовательно
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val workDir by lazy { File(filesDir, "mihomo").apply { mkdirs() } }
    private val logFile by lazy { File(filesDir, LOG_FILE) }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, R.string.notification_title_vpn)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nodeName = intent?.getStringExtra(EXTRA_NODE_NAME)
        startInForeground(nodeName)

        if (intent?.action == ACTION_STOP) {
            _state.value = VpnStatus.STOPPING
            scope.launch { stopVpn(stopService = true) }
            return START_NOT_STICKY
        }

        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId == null || nodeName == null) {
            // Например, перезапуск системой — без явной команды VPN не поднимаем
            scope.launch { stopVpn(stopService = true) }
            return START_NOT_STICKY
        }

        _state.value = VpnStatus.STARTING
        _lastError.value = null
        scope.launch {
            if (coreProcess != null || tunFd != null) stopVpn(stopService = false)
            startVpn(profileId, nodeName)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        scope.launch { stopVpn(stopService = true) }
    }

    // ---------------------------------------------------------------- запуск

    private fun startVpn(profileId: String, nodeName: String) {
        stopping = false
        try {
            val profile = ProfileStore.get(this, profileId) ?: error("Профиль не найден")
            val node = profile.nodes.firstOrNull { it.name == nodeName } ?: error("Сервер «$nodeName» не найден")

            val ports = VpnConfigParser.CorePorts(freePort(), freePort(), UUID.randomUUID().toString())
            var config = VpnConfigParser.buildMihomoConfig(profile.content, node, ports)
            val missing = prefetchProviders(config.providers)
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Правила недоступны и будут пропущены: $missing")
                config = VpnConfigParser.buildMihomoConfig(profile.content, node, ports, missing)
            }
            val configFile = File(workDir, "config.yaml").apply { writeText(config.yaml) }

            startCore(configFile)
            corePorts = ports
            waitForCore(ports)
            startTunnel(ports.mixed)

            activeNodeName = node.name
            _state.value = VpnStatus.RUNNING
            Log.i(TAG, "VPN запущен: ${node.name}")

            if (missing.isNotEmpty()) {
                completeProvidersInBackground(profile.content, node, ports, config.providers, configFile)
            }
            monitorNodeHealth(node.name, ports)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска VPN", e)
            _lastError.value = e.message ?: e.javaClass.simpleName
            stopVpn(stopService = true, failed = true)
        }
    }

    /**
     * Следит, отвечает ли сервер. Один неудачный замер ничего не значит
     * (первое соединение через CDN по LTE бывает долгим), поэтому предупреждение
     * ставится только после нескольких провалов подряд и снимается при первом успехе.
     */
    private fun monitorNodeHealth(nodeName: String, ports: VpnConfigParser.CorePorts) {
        val warning = getString(R.string.vpn_node_unreachable, nodeName)
        thread(name = "node-health", isDaemon = true) {
            var failures = 0
            while (corePorts === ports && _state.value == VpnStatus.RUNNING) {
                val delay = MihomoApi.delay(nodeName, timeoutMs = HEALTH_TIMEOUT_MS) ?: break
                if (corePorts !== ports) break
                if (delay > 0) {
                    failures = 0
                    if (_lastError.value == warning) _lastError.value = null
                } else if (++failures >= HEALTH_FAILURES) {
                    _lastError.value = warning
                }
                Thread.sleep(if (failures in 1 until HEALTH_FAILURES) 2_000 else HEALTH_INTERVAL_MS)
            }
        }
    }

    private fun startCore(configFile: File) {
        killStaleCore()
        val binary = File(applicationInfo.nativeLibraryDir, "libmihomo.so")
        if (!binary.exists()) error("Ядро mihomo не найдено в APK")

        if (logFile.length() > 2L * 1024 * 1024) logFile.writeText("")
        logFile.appendText("\n===== ${java.util.Date()} =====\n")

        val process = ProcessBuilder(binary.absolutePath, "-d", workDir.absolutePath, "-f", configFile.absolutePath)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = workDir.absolutePath }
            .start()
        coreProcess = process
        pidOf(process)?.let { File(workDir, "core.pid").writeText(it.toString()) }

        // Вывод ядра — в лог-файл; неожиданное завершение гасит VPN
        thread(name = "mihomo-log", isDaemon = true) {
            runCatching {
                logFile.appendingWriter().use { writer ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        writer.write(line)
                        writer.write("\n")
                        writer.flush()
                    }
                }
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (!stopping && coreProcess === process) {
                Log.e(TAG, "mihomo завершился с кодом $code")
                _lastError.value = lastCoreError() ?: "ядро mihomo завершилось (код $code)"
                scope.launch { stopVpn(stopService = true, failed = true) }
            }
        }
    }

    private fun File.appendingWriter() = java.io.FileWriter(this, true).buffered()

    /**
     * Ждёт, пока ядро начнёт пропускать трафик. Пока mihomo загружает правила,
     * он принимает SOCKS-соединения, но сразу их закрывает — это и проверяем.
     */
    private fun waitForCore(ports: VpnConfigParser.CorePorts) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var listening = false
        while (System.currentTimeMillis() < deadline) {
            val process = coreProcess ?: error("Ядро остановлено")
            if (!process.isAliveCompat()) {
                error(lastCoreError() ?: "ядро mihomo не запустилось")
            }
            if (!listening) {
                listening = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", ports.mixed), 300) }
                }.isSuccess
            }
            if (listening && isTunnelRunning(ports)) return
            Thread.sleep(200)
        }
        if (!listening) error("ядро mihomo не ответило за ${START_TIMEOUT_MS / 1000} с")
        Log.w(TAG, "Ядро долго загружает правила — запускаем туннель без ожидания")
    }

    /**
     * Запрос к контроллеру самого ядра через его SOCKS (правило 127.0.0.1 → DIRECT).
     * Не зависит от VPN-сервера: успех означает, что ядро пропускает трафик.
     */
    private fun isTunnelRunning(ports: VpnConfigParser.CorePorts): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", ports.mixed), 500)
            socket.soTimeout = 1_500
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write(byteArrayOf(5, 1, 0))
            if (input.read() != 5 || input.read() != 0) return false

            val port = ports.controller
            output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (port shr 8).toByte(), port.toByte()))
            val reply = ByteArray(10)
            var read = 0
            while (read < reply.size) {
                val n = input.read(reply, read, reply.size - read)
                if (n < 0) return false
                read += n
            }
            if (reply[1].toInt() != 0) return false

            output.write(
                ("GET /version HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer ${ports.secret}\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            val status = input.bufferedReader().readLine().orEmpty()
            status.startsWith("HTTP/1.1 200")
        }
    }.getOrDefault(false)

    /**
     * Скачивает недостающие/устаревшие правила напрямую (приложение вне VPN).
     * @return имена провайдеров, для которых файла так и нет
     */
    private fun prefetchProviders(
        providers: List<VpnConfigParser.ProviderFile>,
        socksPort: Int? = null,
        parallelism: Int = providers.size,
        timeoutSec: Long = 12,
    ): Set<String> {
        val now = System.currentTimeMillis()
        val stale = providers.filter { provider ->
            val file = File(workDir, provider.path)
            !file.exists() || file.length() == 0L ||
                (provider.intervalSec > 0 && now - file.lastModified() > provider.intervalSec * 1000)
        }
        if (stale.isEmpty()) return emptySet()

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(minOf(timeoutSec, 8), java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(timeoutSec + 2, java.util.concurrent.TimeUnit.SECONDS)
            .apply {
                if (socksPort != null) {
                    proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                }
            }
            .build()

        val queue = java.util.concurrent.ConcurrentLinkedQueue(stale)
        val threads = List(parallelism.coerceIn(1, stale.size)) { index ->
            thread(name = "rule-fetch-$index", isDaemon = true) {
                while (true) {
                    val provider = queue.poll() ?: break
                    downloadProvider(client, provider)
                }
            }
        }
        threads.forEach { it.join((timeoutSec + 3) * 1000 * stale.size) }
        // Устаревший, но существующий файл лучше, чем ничего — пропускаем только отсутствующие
        return stale.filter { File(workDir, it.path).let { f -> !f.exists() || f.length() == 0L } }
            .map { it.name }
            .toSet()
    }

    private fun downloadProvider(client: okhttp3.OkHttpClient, provider: VpnConfigParser.ProviderFile) {
        runCatching {
            val request = okhttp3.Request.Builder().url(provider.url)
                .header("User-Agent", "clash.meta KKMProxy")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val bytes = response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return
                val target = File(workDir, provider.path)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) tmp.delete()
            }
        }.onFailure { Log.w(TAG, "rule-set ${provider.name}: ${it.message}") }
    }

    /**
     * Правила, не скачанные напрямую, догружаются через уже работающий VPN,
     * после чего ядро перечитывает конфиг — без переподключения.
     */
    private fun completeProvidersInBackground(
        content: String,
        node: VpnConfigParser.VpnNode,
        ports: VpnConfigParser.CorePorts,
        providers: List<VpnConfigParser.ProviderFile>,
        configFile: File,
    ) {
        thread(name = "rule-complete", isDaemon = true) {
            val stillMissing = prefetchProviders(providers, ports.mixed, parallelism = 2, timeoutSec = 25)
            if (corePorts !== ports || _state.value != VpnStatus.RUNNING) return@thread
            val config = VpnConfigParser.buildMihomoConfig(content, node, ports, stillMissing)
            configFile.writeText(config.yaml)
            val reloaded = MihomoApi.reload(configFile.absolutePath)
            Log.i(TAG, "Правила догружены через VPN, пропущено: $stillMissing, перезагрузка: $reloaded")
        }
    }

    private fun startTunnel(socksPort: Int) {
        val tunnelConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $TUN_MTU")
            appendLine("socks5:")
            appendLine("  address: 127.0.0.1")
            appendLine("  port: $socksPort")
            appendLine("  udp: udp")
            appendLine("mapdns:")
            appendLine("  address: $MAPDNS_ADDRESS")
            appendLine("  port: 53")
            appendLine("  network: $MAPDNS_NETWORK")
            appendLine("  netmask: $MAPDNS_NETMASK")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
        }
        val configFile = File(workDir, "tunnel.yaml").apply { writeText(tunnelConfig) }

        val builder = Builder()
            .setSession(getString(R.string.notification_title_vpn))
            .setMtu(TUN_MTU)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(MAPDNS_ADDRESS)
            // IPv6 тоже заворачиваем в туннель, чтобы не было утечек мимо VPN
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("::", 0)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, DashboardActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        // Соединения самого ядра не должны попадать обратно в туннель
        builder.addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val fd = builder.establish() ?: error("Не удалось создать VPN-интерфейс (нет разрешения?)")
        tunFd = fd
        TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
        tunnelStarted = true
    }

    // ---------------------------------------------------------------- остановка

    private fun stopVpn(stopService: Boolean, failed: Boolean = false) {
        stopping = true
        corePorts = null
        activeNodeName = null

        if (tunnelStarted) {
            runCatching { TProxyService.TProxyStopService() }.onFailure { Log.w(TAG, "tun2socks stop", it) }
            tunnelStarted = false
        }
        runCatching { tunFd?.close() }
        tunFd = null

        coreProcess?.let { process ->
            process.destroy()
            val deadline = System.currentTimeMillis() + 2_000
            while (process.isAliveCompat() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            if (process.isAliveCompat()) pidOf(process)?.let { android.os.Process.killProcess(it) }
        }
        coreProcess = null
        File(workDir, "core.pid").delete()

        if (stopService) {
            _state.value = if (failed) VpnStatus.FAILED else VpnStatus.STOPPED
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopping = true
        runCatching { if (tunnelStarted) TProxyService.TProxyStopService() }
        runCatching { tunFd?.close() }
        coreProcess?.destroy()
        coreProcess = null
        corePorts = null
        activeNodeName = null
        if (_state.value != VpnStatus.FAILED) _state.value = VpnStatus.STOPPED
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    /** Ядро, оставшееся от прошлого запуска (например, после падения приложения) */
    private fun killStaleCore() {
        val pidFile = File(workDir, "core.pid")
        val pid = pidFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
        if (cmdline?.contains("libmihomo") == true) android.os.Process.killProcess(pid)
        pidFile.delete()
    }

    private fun lastCoreError(): String? = runCatching {
        logFile.readLines().takeLast(40)
            .lastOrNull { it.contains("level=error") || it.contains("level=fatal") || it.contains("FATA") }
            ?.substringAfter("msg=", "")
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun Process.isAliveCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isAlive
        else runCatching { exitValue(); false }.getOrDefault(true)

    /** PID дочернего процесса (java.lang.UNIXProcess/ProcessImpl.pid) */
    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrNull()

    private fun startInForeground(nodeName: String?) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KkmVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title_vpn))
            .setContentText(nodeName ?: getString(R.string.vpn_notification_content))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.service_stop_btn), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
