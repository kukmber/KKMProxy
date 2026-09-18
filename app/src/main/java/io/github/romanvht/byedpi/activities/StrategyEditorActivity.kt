package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Command
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.ClipboardUtils
import io.github.romanvht.byedpi.utility.HistoryUtils
import io.github.romanvht.byedpi.utility.getCmdArgs
import io.github.romanvht.byedpi.utility.getCmdEnable
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.mode

/** Редактор стратегии byebyeDPI: аргументы командной строки и история. */
class StrategyEditorActivity : BaseActivity() {

    override val useDynamicColors = false

    private val prefs by lazy { getPreferences() }
    private lateinit var history: HistoryUtils
    private lateinit var cmdInput: EditText
    private lateinit var historyList: LinearLayout
    private lateinit var historyEmpty: View
    private lateinit var cmdDisabledCard: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_editor)
        history = HistoryUtils(this)

        val topBar = DashUi.setupTopBar(this, getString(R.string.editor_title))
        topBar.addAction(R.drawable.ic_k_docs, getString(R.string.editor_docs)) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.byedpi_docs))))
        }
        topBar.addAction(R.drawable.ic_k_tune, getString(R.string.editor_advanced)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        cmdInput = findViewById(R.id.cmdInput)
        historyList = findViewById(R.id.historyList)
        historyEmpty = findViewById(R.id.historyEmpty)
        cmdDisabledCard = findViewById(R.id.cmdDisabledCard)

        cmdInput.setText(prefs.getCmdArgs())

        listOf(R.id.pasteButton, R.id.copyButton, R.id.clearButton).forEach {
            DashUi.shrinkDrawables(findViewById(it))
        }
        findViewById<View>(R.id.pasteButton).setOnClickListener {
            ClipboardUtils.paste(this)?.takeIf { it.isNotBlank() }?.let { cmdInput.setText(it.trim()) }
        }
        findViewById<View>(R.id.copyButton).setOnClickListener {
            ClipboardUtils.copy(this, cmdInput.text.toString(), "command")
        }
        findViewById<View>(R.id.clearButton).setOnClickListener { cmdInput.setText("") }
        findViewById<View>(R.id.applyButton).setOnClickListener { applyCommand(cmdInput.text.toString().trim()) }
        findViewById<View>(R.id.historyMenuButton).setOnClickListener { showHistoryClearDialog() }
        findViewById<View>(R.id.enableCmdButton).setOnClickListener {
            prefs.edit { putBoolean("byedpi_enable_cmd_settings", true) }
            updateCmdWarning()
            restartService()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCmdWarning()
        renderHistory()
    }

    private fun updateCmdWarning() {
        cmdDisabledCard.visibility = if (prefs.getCmdEnable()) View.GONE else View.VISIBLE
    }

    private fun applyCommand(command: String) {
        prefs.edit(commit = true) { putString("byedpi_cmd_args", command) }
        if (command.isNotBlank()) history.addCommand(command)
        if (cmdInput.text.toString().trim() != command) cmdInput.setText(command)
        renderHistory()
        if (!restartService()) Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
    }

    /** @return true, если сервис был перезапущен */
    private fun restartService(): Boolean {
        if (appStatus.first != AppStatus.Running) return false
        val mode = prefs.mode()
        if (mode == Mode.VPN && VpnService.prepare(this) != null) return false
        ServiceManager.restart(this, mode)
        Toast.makeText(this, R.string.service_restart, Toast.LENGTH_SHORT).show()
        return true
    }

    // ---------------------------------------------------------------- история

    private fun renderHistory() {
        historyList.removeAllViews()
        val items = history.getHistory()
        val current = prefs.getCmdArgs()
        historyEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        items.sortedWith(compareByDescending<Command> { it.pinned }.thenBy { items.indexOf(it) })
            .forEach { command ->
                val view = layoutInflater.inflate(R.layout.item_dash_history, historyList, false)
                val name = view.findViewById<TextView>(R.id.historyName)
                name.text = command.name.orEmpty()
                val showHeader = !command.name.isNullOrBlank() || command.pinned || command.text == current
                (name.parent as View).visibility = if (showHeader) View.VISIBLE else View.GONE
                view.findViewById<TextView>(R.id.historyCommand).text = command.text
                view.findViewById<View>(R.id.historyPin).visibility = if (command.pinned) View.VISIBLE else View.GONE
                view.findViewById<View>(R.id.historyActive).visibility =
                    if (command.text == current) View.VISIBLE else View.GONE
                view.setOnClickListener { showActions(command) }
                view.findViewById<ImageView>(R.id.historyMore).setOnClickListener { showActions(command) }
                historyList.addView(view)
            }
    }

    private fun showActions(command: Command) {
        val options = arrayOf(
            getString(R.string.cmd_history_apply),
            getString(if (command.pinned) R.string.cmd_history_unpin else R.string.cmd_history_pin),
            getString(R.string.cmd_history_rename),
            getString(R.string.cmd_history_edit),
            getString(R.string.cmd_history_copy),
            getString(R.string.cmd_history_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(command.name?.takeIf { it.isNotBlank() } ?: getString(R.string.cmd_history_menu))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyCommand(command.text)
                    1 -> {
                        if (command.pinned) history.unpinCommand(command.text) else history.pinCommand(command.text)
                        renderHistory()
                    }
                    2 -> DashUi.inputDialog(
                        this,
                        getString(R.string.cmd_history_rename),
                        getString(R.string.domain_list_name_hint),
                        command.name,
                    ) { newName, _ ->
                        history.renameCommand(command.text, newName.trim())
                        renderHistory()
                    }
                    3 -> DashUi.inputDialog(
                        this,
                        getString(R.string.cmd_history_edit),
                        getString(R.string.editor_args),
                        command.text,
                    ) { newText, _ ->
                        val text = newText.trim()
                        if (text.isBlank() || text == command.text) return@inputDialog
                        val wasCurrent = prefs.getCmdArgs() == command.text
                        history.editCommand(command.text, text)
                        if (wasCurrent) applyCommand(text) else renderHistory()
                    }
                    4 -> ClipboardUtils.copy(this, command.text, "command")
                    5 -> {
                        history.deleteCommand(command.text)
                        renderHistory()
                    }
                }
            }
            .show()
    }

    private fun showHistoryClearDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cmd_history_menu)
            .setItems(
                arrayOf(
                    getString(R.string.cmd_history_delete_unpinned),
                    getString(R.string.cmd_history_delete_all),
                ),
            ) { _, which ->
                if (which == 0) history.clearUnpinnedHistory() else history.clearAllHistory()
                renderHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
