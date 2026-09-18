package io.github.romanvht.byedpi.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.romanvht.byedpi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Живой просмотр логов одного раздела (VPN / TgWsProxy / byebyeDPI). */
class LogActivity : BaseActivity() {

    enum class Source(val titleRes: Int, val tags: List<String>, val fileName: String?) {
        VPN(
            R.string.logs_title_vpn,
            listOf("KkmVpnService", "hev-socks5-tunnel"),
            io.github.romanvht.byedpi.vpn.KkmVpnService.LOG_FILE,
        ),
        TGWS(
            R.string.logs_title_tgws,
            listOf(
                "TelegramProxyService", "TgWsProxyServer", "TelegramProxy",
                "Bridge", "WsPool", "CfProxyDomains",
            ),
            null,
        ),
        BYEDPI(
            R.string.logs_title_byedpi,
            listOf("proxy", "ByeDpiVpnService", "ByeDpiProxyService", "ServiceManager", "TProxyService", "hev-socks5-tunnel"),
            null,
        ),
    }

    override val useDynamicColors = false

    private lateinit var source: Source
    private lateinit var topBar: DashUi.TopBar
    private lateinit var logText: TextView
    private lateinit var logEmpty: View
    private lateinit var scroll: NestedScrollView
    private lateinit var toBottom: View
    private lateinit var autoscrollChip: TextView

    private var autoscroll = true
    private var filter = ""
    private var lastRaw: List<String> = emptyList()
    private val prefs by lazy { getSharedPreferences("log_viewer", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        source = runCatching { Source.valueOf(intent.getStringExtra(EXTRA_SOURCE).orEmpty()) }
            .getOrDefault(Source.VPN)

        logText = findViewById(R.id.logText)
        logEmpty = findViewById(R.id.logEmpty)
        scroll = findViewById(R.id.logScroll)
        toBottom = findViewById(R.id.logToBottom)
        autoscrollChip = findViewById(R.id.logAutoscroll)

        topBar = DashUi.setupTopBar(this, getString(source.titleRes))
        topBar.addAction(R.drawable.ic_k_copy, getString(R.string.cmd_history_copy)) { copyLogs() }
        topBar.addAction(R.drawable.ic_k_share, getString(R.string.save_logs)) { shareLogs() }
        topBar.addAction(R.drawable.ic_k_clear, getString(R.string.cmd_args_clear)) { clearLogs() }

        autoscrollChip.isSelected = true
        autoscrollChip.setOnClickListener {
            autoscroll = !autoscroll
            autoscrollChip.isSelected = autoscroll
            if (autoscroll) scrollToBottom()
        }
        toBottom.setOnClickListener {
            autoscroll = true
            autoscrollChip.isSelected = true
            scrollToBottom()
        }
        scroll.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            val atBottom = scrollY + v.height >= logText.height - 32
            toBottom.visibility = if (atBottom) View.GONE else View.VISIBLE
            if (scrollY < oldScrollY && !atBottom && autoscroll) {
                autoscroll = false
                autoscrollChip.isSelected = false
            }
        })

        findViewById<EditText>(R.id.logFilter).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim().orEmpty()
                render(lastRaw, force = true)
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    val lines = withContext(Dispatchers.IO) { readLines() }
                    render(lines)
                    delay(REFRESH_MS)
                }
            }
        }
    }

    // ---------------------------------------------------------------- чтение

    private fun readLines(): List<String> {
        val result = mutableListOf<String>()
        val logcat = readLogcat()
        val file = source.fileName?.let { File(filesDir, it) }

        if (file != null) {
            if (logcat.isNotEmpty()) {
                result += "── сервис ──"
                result += logcat
                result += "── ядро mihomo ──"
            }
            result += readFileTail(file)
        } else {
            result += logcat
        }
        return result.takeLast(MAX_LINES)
    }

    private fun readLogcat(): List<String> = runCatching {
        val command = mutableListOf("logcat", "-d", "-v", "time", "-t", MAX_LINES.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) command += "--pid=${Process.myPid()}"
        command += "-s"
        command += source.tags.map { "$it:V" }

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val clearedAt = prefs.getString(clearKey(), null)
        val lines = process.inputStream.bufferedReader().useLines { seq ->
            seq.filter { it.isNotBlank() && !it.startsWith("-----") }
                .filter { clearedAt == null || it.take(TIME_LENGTH) > clearedAt }
                .toList()
        }
        process.destroy()
        lines
    }.getOrDefault(emptyList())

    private fun readFileTail(file: File): List<String> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - MAX_FILE_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                val lines = String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }
                if (start > 0) lines.drop(1) else lines
            }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- отрисовка

    private fun render(lines: List<String>, force: Boolean = false) {
        if (!force && lines == lastRaw) return
        lastRaw = lines
        val visible = if (filter.isEmpty()) lines else lines.filter { it.contains(filter, ignoreCase = true) }

        topBar.setSubtitle(getString(R.string.logs_lines, visible.size))
        logEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE

        val error = ContextCompat.getColor(this, R.color.danger)
        val warn = ContextCompat.getColor(this, R.color.warn)
        val muted = ContextCompat.getColor(this, R.color.muted)
        val accent = ContextCompat.getColor(this, R.color.accent)

        val text = SpannableStringBuilder()
        visible.forEach { line ->
            val start = text.length
            text.append(line).append('\n')
            val color = when {
                line.startsWith("──") -> accent
                ERROR_REGEX.containsMatchIn(line) -> error
                WARN_REGEX.containsMatchIn(line) -> warn
                DEBUG_REGEX.containsMatchIn(line) -> muted
                else -> null
            }
            if (color != null) {
                text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        logText.text = text
        if (autoscroll) scrollToBottom()
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- действия

    private fun currentText(): String =
        (if (filter.isEmpty()) lastRaw else lastRaw.filter { it.contains(filter, true) })
            .joinToString("\n")
            .takeLast(MAX_SHARE_CHARS)

    private fun copyLogs() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(source.titleRes), currentText()))
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(source.titleRes))
            .putExtra(Intent.EXTRA_TEXT, currentText())
        startActivity(Intent.createChooser(intent, getString(source.titleRes)))
    }

    private fun clearLogs() {
        source.fileName?.let { name -> runCatching { File(filesDir, name).writeText("") } }
        // Буфер logcat приложению очищать нельзя, поэтому просто скрываем старые строки
        val now = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        prefs.edit { putString(clearKey(), now) }
        render(emptyList(), force = true)
        Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun clearKey() = "cleared_${source.name}"

    companion object {
        const val EXTRA_SOURCE = "log_source"

        private const val MAX_LINES = 1500
        private const val MAX_FILE_BYTES = 256 * 1024L
        private const val MAX_SHARE_CHARS = 200_000
        private const val REFRESH_MS = 1500L
        private const val TIME_LENGTH = 18

        private val ERROR_REGEX = Regex("""(^\S+ \S+ [EF]/)|\bERROR\b|\bFATAL\b|level=(error|fatal)""")
        private val WARN_REGEX = Regex("""(^\S+ \S+ W/)|\bWARN(ING)?\b|level=warn""")
        private val DEBUG_REGEX = Regex("""(^\S+ \S+ [DV]/)|\bDEBUG\b|\bTRACE\b|level=debug""")

        fun open(context: Context, source: Source) {
            context.startActivity(Intent(context, LogActivity::class.java).putExtra(EXTRA_SOURCE, source.name))
        }
    }
}
