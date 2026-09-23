package io.github.romanvht.byedpi.activities

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.update.Release
import io.github.romanvht.byedpi.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Обновление приложения и версии компонентов из оригинальных репозиториев. */
class UpdatesActivity : BaseActivity() {

    override val useDynamicColors = false

    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var checkButton: MaterialButton
    private lateinit var installButton: MaterialButton
    private lateinit var repoValue: TextView

    private var pending: Release? = null
    private var downloadId = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
            if (id == downloadId) installDownloaded(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates)
        DashUi.setupTopBar(this, getString(R.string.updates_title))

        status = findViewById(R.id.updateStatus)
        notes = findViewById(R.id.releaseNotes)
        checkButton = findViewById(R.id.checkButton)
        installButton = findViewById(R.id.installButton)
        repoValue = findViewById(R.id.repoValue)

        findViewById<TextView>(R.id.currentVersion).text =
            getString(R.string.updates_version, Updates.currentVersion())
        checkButton.setOnClickListener { checkAll() }
        installButton.setOnClickListener { pending?.let(::download) }
        findViewById<View>(R.id.repoRow).setOnClickListener { editRepo() }

        renderComponents(emptyMap())
        showRepo()
        registerDownloadReceiver()
        checkAll()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        super.onDestroy()
    }

    private fun showRepo() {
        val repo = Updates.repo(this)
        repoValue.text = repo.ifBlank { getString(R.string.updates_repo_empty) }
    }

    private fun editRepo() {
        DashUi.inputDialog(
            activity = this,
            title = getString(R.string.updates_repo),
            firstHint = getString(R.string.updates_repo_hint),
            firstValue = Updates.repo(this),
            hint = getString(R.string.updates_repo_note),
        ) { value, _ ->
            // Принимаем и «user/repo», и полную ссылку на GitHub
            val repo = value.trim()
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("github.com/")
                .trim('/')
            Updates.setRepo(this, repo)
            showRepo()
            checkAll()
        }
    }

    // ---------------------------------------------------------------- проверка

    private fun checkAll() {
        val repo = Updates.repo(this)
        checkButton.isEnabled = false
        status.setText(R.string.updates_checking)
        installButton.visibility = View.GONE
        notes.visibility = View.GONE

        lifecycleScope.launch {
            val appRelease = if (repo.isBlank()) null else withContext(Dispatchers.IO) {
                Updates.latestRelease(repo)
            }
            val latest = withContext(Dispatchers.IO) {
                Updates.components.associate { it.repo to Updates.latestRelease(it.repo)?.tag }
            }
            checkButton.isEnabled = true
            renderComponents(latest)

            when {
                repo.isBlank() -> status.setText(R.string.updates_no_repo)
                appRelease == null -> status.setText(R.string.updates_failed)
                Updates.isNewer(appRelease.tag, Updates.currentVersion()) -> {
                    pending = appRelease
                    // Пришли по кнопке «Обновить» — качаем сразу, без лишнего нажатия
                    if (intent.getBooleanExtra(EXTRA_AUTO_DOWNLOAD, false)) {
                        intent.removeExtra(EXTRA_AUTO_DOWNLOAD)
                        download(appRelease)
                    }
                    Updates.markSeen(this@UpdatesActivity, appRelease.tag)
                    status.text = getString(R.string.updates_available, appRelease.name)
                    notes.text = appRelease.notes.trim().ifBlank { getString(R.string.updates_no_notes) }
                    notes.visibility = View.VISIBLE
                    installButton.visibility = View.VISIBLE
                    installButton.text = if (appRelease.apkUrl != null) {
                        getString(R.string.updates_install_size, appRelease.apkSize / 1_048_576.0)
                    } else {
                        getString(R.string.updates_open_release)
                    }
                }
                else -> status.setText(R.string.updates_latest)
            }
        }
    }

    private fun renderComponents(latest: Map<String, String?>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.componentsList)
        list.removeAllViews()
        Updates.components.forEach { component ->
            val view = layoutInflater.inflate(R.layout.item_component, list, false)
            view.findViewById<TextView>(R.id.componentTitle).text = component.title
            view.findViewById<TextView>(R.id.componentDetails).text =
                getString(R.string.updates_component_details, component.repo, component.bundled)
            val tag = view.findViewById<TextView>(R.id.componentTag)
            val upstream = latest[component.repo]
            when {
                upstream == null -> tag.visibility = View.GONE
                Updates.isNewer(upstream, component.bundled) -> {
                    tag.visibility = View.VISIBLE
                    tag.text = upstream
                    tag.setBackgroundResource(R.drawable.bg_status_tag_warn)
                    tag.setTextColor(getColor(R.color.warn))
                }
                else -> {
                    tag.visibility = View.VISIBLE
                    tag.setText(R.string.updates_component_actual)
                    tag.setBackgroundResource(R.drawable.bg_status_tag_on)
                    tag.setTextColor(getColor(R.color.accent))
                }
            }
            view.setOnClickListener { openUrl("https://github.com/${component.repo}/releases") }
            list.addView(view)
        }
    }

    // ---------------------------------------------------------------- установка

    private fun download(release: Release) {
        val url = release.apkUrl ?: return openUrl(release.pageUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.updates_permission_title)
                .setMessage(R.string.updates_permission_text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                .show()
            return
        }

        val fileName = "KKMProxy-${release.tag.trimStart('v')}.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(getString(R.string.updates_title))
            .setDescription(release.name)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
        downloadId = getSystemService(DownloadManager::class.java).enqueue(request)
        installButton.isEnabled = false
        status.setText(R.string.updates_downloading)
    }

    private fun installDownloaded(id: Long) {
        val manager = getSystemService(DownloadManager::class.java)
        val uri = manager.getUriForDownloadedFile(id)
        installButton.isEnabled = true
        if (uri == null) {
            status.setText(R.string.updates_failed)
            return
        }
        status.setText(R.string.updates_downloaded)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.updates_failed, Toast.LENGTH_LONG).show() }
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        /** Начать скачивание сразу после проверки */
        const val EXTRA_AUTO_DOWNLOAD = "auto_download"
    }
}
