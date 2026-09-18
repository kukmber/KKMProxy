package io.github.romanvht.byedpi.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.FAILED_BROADCAST
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.STARTED_BROADCAST
import io.github.romanvht.byedpi.data.STOPPED_BROADCAST
import io.github.romanvht.byedpi.databinding.ActivityDashboardBinding
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.telegram.TelegramProxyService
import io.github.romanvht.byedpi.telegram.TelegramProxyState
import io.github.romanvht.byedpi.telegram.TelegramProxyStatus
import io.github.romanvht.byedpi.telegram.proxy.ProxyStats
import io.github.romanvht.byedpi.update.Updates
import io.github.romanvht.byedpi.utility.LauncherIcons
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.mode
import io.github.romanvht.byedpi.vpn.GeoIp
import io.github.romanvht.byedpi.vpn.MihomoApi
import io.github.romanvht.byedpi.vpn.ProfileStore
import io.github.romanvht.byedpi.vpn.VpnStatus
import io.github.romanvht.byedpi.vpn.KkmVpnService
import io.github.romanvht.byedpi.vpn.VpnConfigParser
import io.github.romanvht.byedpi.vpn.VpnConfigParser.VpnNode
import io.github.romanvht.byedpi.vpn.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

class DashboardActivity : BaseActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private val preferences by lazy { getPreferences() }

    private var openSheet: BottomSheetDialog? = null
    private var connectJob: Job? = null
    private var nodeInfoJob: Job? = null

    private var lastTrafficBytes = -1L
    private var lastTrafficTime = 0L

    override val useDynamicColors = false

    private val tabs by lazy {
        listOf(
            binding.tabVpn to binding.vpnPanel,
            binding.tabTgws to binding.tgwsPanel,
            binding.tabDpi to binding.byedpiPanel,
            binding.tabSettings to binding.settingsPanel,
        )
    }

    private val protocol: String
        get() = preferences.getString(PREF_PROTOCOL, VpnConfigParser.TYPE_HYSTERIA2)
            ?: VpnConfigParser.TYPE_HYSTERIA2

    private val singBoxPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) connectVpn()
        else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
    }

    private val byeDpiPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) startByeDpi(Mode.VPN)
        else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(::importText)
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateByeDpiStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        shrinkCompoundDrawables(binding.root)

        binding.profilePill.setOnClickListener { showProfilesSheet() }
        binding.addProfileButton.setOnClickListener { showAddSheet() }
        setupNavigation(savedInstanceState?.getInt(STATE_TAB) ?: 0)
        setupVpn()
        setupTelegram()
        setupByeDpi()
        setupSettings()
        registerServiceReceiver()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TelegramProxyService.state.collect(::updateTelegramState) }
                launch { KkmVpnService.state.collect(::updateVpnState) }
                launch { KkmVpnService.lastError.collect { updateVpnState(KkmVpnService.state.value) } }
                launch {
                    while (isActive) {
                        updateTelegramStats()
                        updateVpnStats()
                        delay(1_000)
                    }
                }
            }
        }

        refreshProfileUi()
        loadSelectedProfileIfEmpty()
        lifecycleScope.launch(Dispatchers.IO) { LauncherIcons.update(applicationContext) }
    }

    override fun onResume() {
        super.onResume()
        updateByeDpiStatus()
        updateTelegramState(TelegramProxyService.state.value)
        updateVpnState(KkmVpnService.state.value)
        binding.tgLinkText.text = TelegramProxyService.proxyLink(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tabs.indexOfFirst { it.first.isSelected }.coerceAtLeast(0))
    }

    override fun onDestroy() {
        unregisterReceiver(serviceReceiver)
        openSheet?.dismiss()
        openSheet = null
        super.onDestroy()
    }

    // ================================================================ навигация

    private fun setupNavigation(initialTab: Int) {
        tabs.forEachIndexed { index, (tab, _) -> tab.setOnClickListener { selectTab(index) } }
        selectTab(initialTab)
    }

    private fun selectTab(index: Int) {
        tabs.forEachIndexed { i, (tab, panel) ->
            tab.isSelected = i == index
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        // Логотип Telegram цветной — вместо перекраски приглушаем его
        binding.tabTgwsIcon.alpha = if (index == 1) 1f else 0.55f
    }

    // ================================================================ профили

    private fun refreshProfileUi() {
        val profiles = ProfileStore.all(this)
        val profile = ProfileStore.selected(this)
        val name = profile?.name ?: getString(R.string.dash_profile_empty)
        binding.profileName.text = name
        binding.profileAvatar.text = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "K"
        binding.profileMeta.text = profile?.let {
            getString(R.string.dash_profile_nodes, it.nodes.count { node -> node.isSupported })
        }.orEmpty()
        binding.profilesCount.text = getString(R.string.dash_profiles_count, profiles.size)
        updateNodeCard()
    }

    private fun loadSelectedProfileIfEmpty() {
        val profile = ProfileStore.selected(this) ?: return
        if (!profile.canUpdate || profile.content.isNotBlank()) return
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { ProfileStore.refresh(applicationContext, profile) } }
            refreshProfileUi()
        }
    }

    private fun showProfilesSheet() {
        val sheet = createListSheet(getString(R.string.dash_profiles))
        sheet.addAction(R.drawable.ic_k_refresh, getString(R.string.dash_update_all)) { updateAllProfiles() }
        sheet.addAction(R.drawable.ic_k_add, getString(R.string.dash_add_config)) {
            sheet.dialog.dismiss()
            showAddSheet()
        }

        fun render() {
            sheet.list.removeAllViews()
            val profiles = ProfileStore.all(this)
            val selectedId = ProfileStore.selected(this)?.id
            sheet.setEmpty(if (profiles.isEmpty()) getString(R.string.dash_profile_empty) else null)

            profiles.forEach { profile ->
                val entry = sheet.addEntry(
                    title = profile.name,
                    subtitle = profileSubtitle(profile),
                    icon = when (profile.source) {
                        VpnProfile.SOURCE_URL -> R.drawable.ic_k_link
                        VpnProfile.SOURCE_FILE -> R.drawable.ic_k_file
                        else -> R.drawable.ic_k_qr
                    },
                    selected = profile.id == selectedId,
                )
                entry.view.setOnClickListener {
                    ProfileStore.select(this, profile.id)
                    sheet.dialog.dismiss()
                    refreshProfileUi()
                    if (profile.content.isBlank()) refreshProfile(profile) else reconnectIfActive()
                }
                if (profile.canUpdate) {
                    entry.setAction(R.drawable.ic_k_refresh, getString(R.string.dash_update_all)) {
                        entry.subtitle.text = getString(R.string.dash_profile_updating)
                        refreshProfile(profile) { render() }
                    }
                }
                entry.setMore { showProfileMenu(profile) { render() } }
            }
        }
        render()
        sheet.dialog.show()
    }

    private fun profileSubtitle(profile: VpnProfile): String {
        val parts = mutableListOf(
            getString(
                when (profile.source) {
                    VpnProfile.SOURCE_URL -> R.string.dash_source_subscription
                    VpnProfile.SOURCE_FILE -> R.string.dash_source_file
                    else -> R.string.dash_source_link
                },
            ),
        )
        if (profile.content.isBlank()) {
            parts += getString(R.string.dash_profile_never)
        } else {
            parts += getString(R.string.dash_profile_nodes, profile.nodes.count { it.isSupported })
            if (profile.updatedAt > 0) {
                parts += getString(
                    R.string.dash_profile_updated,
                    DateFormat.format("dd.MM HH:mm", profile.updatedAt).toString(),
                )
            }
        }
        profile.trafficSummary()?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun showProfileMenu(profile: VpnProfile, onChanged: () -> Unit) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.dash_profile_rename) to {
            DashUi.inputDialog(this, getString(R.string.dash_profile_rename), getString(R.string.profile_name), profile.name) { name, _ ->
                if (name.isNotBlank()) {
                    ProfileStore.upsert(this, profile.copy(name = name.trim()))
                    refreshProfileUi()
                    onChanged()
                }
            }
        }
        profile.url?.let { url ->
            actions += getString(R.string.cmd_history_copy) to { copyToClipboard("subscription", url) }
        }
        actions += getString(R.string.cmd_history_delete) to {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dash_profile_delete_title, profile.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.cmd_history_delete) { _, _ ->
                    ProfileStore.delete(this, profile.id)
                    refreshProfileUi()
                    onChanged()
                }
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle(profile.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun refreshProfile(profile: VpnProfile, onDone: (() -> Unit)? = null) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { ProfileStore.refresh(applicationContext, profile) }
            }
            result.onSuccess { toast(R.string.dash_profile_update_done) }
                .onFailure { toast(getString(R.string.dash_error, it.message ?: it.javaClass.simpleName)) }
            refreshProfileUi()
            onDone?.invoke()
            if (result.isSuccess && profile.id == ProfileStore.selected(this@DashboardActivity)?.id) {
                reconnectIfActive()
            }
        }
    }

    private fun updateAllProfiles() {
        lifecycleScope.launch {
            toast(R.string.dash_profile_updating)
            val errors = withContext(Dispatchers.IO) {
                ProfileStore.all(applicationContext).filter { it.canUpdate }.mapNotNull { profile ->
                    runCatching { ProfileStore.refresh(applicationContext, profile) }
                        .exceptionOrNull()?.let { "${profile.name}: ${it.message}" }
                }
            }
            if (errors.isEmpty()) toast(R.string.dash_profile_update_done)
            else toast(getString(R.string.dash_error, errors.joinToString("\n")))
            refreshProfileUi()
            openSheet?.dismiss()
        }
    }

    // ---------------------------------------------------------------- добавление

    private fun showAddSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_profile, null)
        view.findViewById<View>(R.id.sheetImportUrl).setOnClickListener {
            dialog.dismiss()
            showSubscriptionDialog()
        }
        view.findViewById<View>(R.id.sheetImportClipboard).setOnClickListener {
            dialog.dismiss()
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrBlank()) toast(R.string.dash_clipboard_empty) else importText(text)
        }
        view.findViewById<View>(R.id.sheetImportQr).setOnClickListener {
            dialog.dismiss()
            qrScanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.dash_qr_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(true),
            )
        }
        view.findViewById<View>(R.id.sheetImportFile).setOnClickListener {
            dialog.dismiss()
            filePicker.launch(arrayOf("application/x-yaml", "text/yaml", "text/plain", "application/octet-stream", "*/*"))
        }
        showSheet(dialog, view)
    }

    private fun showSubscriptionDialog(prefillUrl: String? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_dash_inputs, null)
        val url = view.findViewById<EditText>(R.id.inputFirst)
        val name = view.findViewById<EditText>(R.id.inputSecond)
        val progress = view.findViewById<View>(R.id.inputProgress)
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputFirstLayout).hint =
            getString(R.string.dash_subscription_url)
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.inputSecondLayout).hint =
            getString(R.string.dash_subscription_name)
        url.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        url.setText(prefillUrl)
        name.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.minLines = 1
        name.maxLines = 1
        name.typeface = android.graphics.Typeface.DEFAULT
        view.findViewById<TextView>(R.id.inputHint).apply {
            setText(R.string.dash_subscription_hint)
            visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dash_subscription_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.dash_add, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val link = url.text?.toString()?.trim().orEmpty()
            if (!link.startsWith("http://") && !link.startsWith("https://")) {
                url.error = getString(R.string.dash_subscription_url)
                return@setOnClickListener
            }
            button.isEnabled = false
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        ProfileStore.addSubscription(applicationContext, link, name.text?.toString())
                    }
                }
                progress.visibility = View.GONE
                button.isEnabled = true
                result.onSuccess { profile ->
                    dialog.dismiss()
                    toast(getString(R.string.dash_profile_added, profile.name))
                    refreshProfileUi()
                    reconnectIfActive()
                }.onFailure {
                    url.error = it.message ?: it.javaClass.simpleName
                }
            }
        }
    }

    /** Текст из QR/буфера: ссылка на подписку или ссылки на узлы. */
    private fun importText(raw: String) {
        val text = raw.trim()
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            showSubscriptionDialog(text.lineSequence().first().trim())
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ProfileStore.addContent(applicationContext, text, "", VpnProfile.SOURCE_LINK)
                }
            }
            result.onSuccess {
                toast(getString(R.string.dash_profile_added, it.name))
                refreshProfileUi()
                reconnectIfActive()
            }.onFailure { toast(getString(R.string.dash_error, it.message ?: "")) }
        }
    }

    private fun importFile(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error(getString(R.string.yaml_import_failed))
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?.substringBeforeLast('.')
                        .orEmpty()
                    ProfileStore.addContent(applicationContext, text, name, VpnProfile.SOURCE_FILE)
                }
            }
            result.onSuccess {
                toast(getString(R.string.dash_profile_added, it.name))
                refreshProfileUi()
                reconnectIfActive()
            }.onFailure { toast(getString(R.string.dash_error, it.message ?: "")) }
        }
    }

    // ================================================================ VPN

    private fun setupVpn() {
        showProtocol(protocol)
        binding.protocolVless.setOnClickListener { selectProtocol(VpnConfigParser.TYPE_VLESS) }
        binding.protocolHysteria.setOnClickListener { selectProtocol(VpnConfigParser.TYPE_HYSTERIA2) }
        binding.vpnCountryCard.setOnClickListener { showNodesSheet() }

        binding.vpnConnectButton.setOnClickListener {
            when (KkmVpnService.state.value) {
                VpnStatus.STOPPED, VpnStatus.FAILED -> requestVpnConnection()
                else -> {
                    connectJob?.cancel()
                    KkmVpnService.stop(this)
                }
            }
        }
        binding.vpnLogsButton.setOnClickListener { LogActivity.open(this, LogActivity.Source.VPN) }
    }

    private fun selectProtocol(newProtocol: String) {
        if (protocol == newProtocol) return
        preferences.edit { putString(PREF_PROTOCOL, newProtocol) }
        showProtocol(newProtocol)
        updateNodeCard()
        reconnectIfActive()
    }

    private fun showProtocol(value: String) {
        binding.protocolVless.isSelected = value == VpnConfigParser.TYPE_VLESS
        binding.protocolHysteria.isSelected = value != VpnConfigParser.TYPE_VLESS
    }

    /** Узел, который будет использован для текущего профиля и протокола. */
    private fun resolveNode(profile: VpnProfile?): VpnNode? {
        profile ?: return null
        val nodes = profile.nodes.filter { it.type == protocol }
        val preferred = profile.selectedNodes[protocol]
        return nodes.firstOrNull { it.name == preferred && it.isSupported } ?: nodes.firstOrNull { it.isSupported }
    }

    private fun updateNodeCard() {
        val node = resolveNode(ProfileStore.selected(this))
        nodeInfoJob?.cancel()
        if (node == null) {
            binding.vpnCountry.text = "—"
            binding.vpnNode.setText(
                if (ProfileStore.selected(this) == null) R.string.dash_no_profile else R.string.dash_nodes_empty,
            )
            return
        }
        binding.vpnNode.text = node.name
        binding.vpnCountry.text = countryLabel(node.countryCode) ?: node.server
        nodeInfoJob = lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { countryFor(node) }
            countryLabel(code)?.let { binding.vpnCountry.text = it }
        }
    }

    private fun countryLabel(code: String?): String? =
        code?.let { listOfNotNull(VpnConfigParser.codeToFlag(it), it).joinToString(" ") }

    /** Страна узла: из имени, иначе по IP сервера. Не в главном потоке. */
    private fun countryFor(node: VpnNode): String? {
        node.countryCode?.let { return it }
        val ip = resolvedIps.getOrPut(node.server) { VpnConfigParser.resolveServer(node.server) ?: "" }
        if (ip.isEmpty()) return null
        return GeoIp.lookup(applicationContext, ip)
    }

    private fun showNodesSheet() {
        val profile = ProfileStore.selected(this)
        if (profile == null) {
            toast(R.string.dash_no_profile)
            showAddSheet()
            return
        }
        val protocolName = if (protocol == VpnConfigParser.TYPE_VLESS) "VLESS" else "Hysteria2"
        val sheet = createListSheet(getString(R.string.dash_nodes_title, protocolName))
        val nodes = profile.nodes.filter { it.type == protocol }
        val current = resolveNode(profile)
        sheet.setEmpty(if (nodes.isEmpty()) getString(R.string.dash_nodes_empty) else null)

        nodes.forEach { node ->
            val entry = sheet.addEntry(
                title = node.name,
                subtitle = node.unsupportedReason?.let { getString(R.string.dash_node_unsupported, it) }
                    ?: "${node.server}:${node.port} · ${node.network}",
                icon = R.drawable.ic_k_globe,
                selected = node.name == current?.name,
            )
            if (!node.isSupported) {
                entry.view.alpha = 0.45f
                entry.view.isEnabled = false
            } else {
                entry.view.setOnClickListener {
                    ProfileStore.selectNode(this, profile.id, protocol, node.name)
                    sheet.dialog.dismiss()
                    updateNodeCard()
                    reconnectIfActive()
                }
            }
            node.countryCode?.let { entry.setEmoji(VpnConfigParser.codeToFlag(it)) }
            if (!node.isSupported) return@forEach
            lifecycleScope.launch {
                var subtitle = "${node.server}:${node.port} · ${node.network}"
                withContext(Dispatchers.IO) { countryFor(node) }?.let { code ->
                    entry.setEmoji(VpnConfigParser.codeToFlag(code))
                    subtitle = "$code · $subtitle"
                    entry.subtitle.text = subtitle
                }
                // Пинг умеет считать только запущенное ядро
                if (KkmVpnService.state.value != VpnStatus.RUNNING) return@launch
                entry.subtitle.text = "$subtitle · …"
                val delay = withContext(Dispatchers.IO) { MihomoApi.delay(node.name) } ?: return@launch
                entry.subtitle.text = if (delay > 0) {
                    "$subtitle · $delay ms"
                } else {
                    "$subtitle · ${getString(R.string.dash_node_timeout)}"
                }
            }
        }
        sheet.dialog.show()
    }

    private fun reconnectIfActive() {
        val state = KkmVpnService.state.value
        if (state == VpnStatus.RUNNING || state == VpnStatus.STARTING) connectVpn()
    }

    private fun requestVpnConnection() {
        if (ProfileStore.selected(this) == null) {
            toast(R.string.dash_no_profile)
            showAddSheet()
            return
        }
        val permission = VpnService.prepare(this)
        if (permission == null) connectVpn() else singBoxPermission.launch(permission)
    }

    private fun connectVpn() {
        connectJob?.cancel()
        showVpnConnecting()
        connectJob = lifecycleScope.launch {
            try {
                val (profile, node) = withContext(Dispatchers.IO) {
                    var profile = ProfileStore.selected(applicationContext) ?: error(getString(R.string.dash_no_profile))
                    if (profile.content.isBlank() && profile.canUpdate) {
                        profile = ProfileStore.refresh(applicationContext, profile)
                    }
                    profile to (resolveNode(profile) ?: error(getString(R.string.vpn_no_nodes)))
                }

                // VPN может быть только один: гасим конкурентов
                if (TelegramProxyService.state.value.status == TelegramProxyStatus.RUNNING) {
                    TelegramProxyService.stop(this@DashboardActivity)
                }
                if (appStatus.first == AppStatus.Running && appStatus.second == Mode.VPN) {
                    ServiceManager.stop(this@DashboardActivity)
                }

                updateNodeCard()
                KkmVpnService.start(this@DashboardActivity, profile.id, node.name)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                connectJob = null
                updateVpnState(KkmVpnService.state.value)
                showVpnError(getString(R.string.dash_vpn_failed, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun showVpnConnecting() {
        binding.vpnError.visibility = View.GONE
        binding.vpnConnectVerb.setText(R.string.vpn_disconnect)
        setStatusTag(binding.vpnStatusTag, R.string.dash_tag_connecting, active = false, pending = true)
    }

    private fun showVpnError(message: String?) {
        binding.vpnError.text = message
        binding.vpnError.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun updateVpnState(status: VpnStatus) {
        val running = status == VpnStatus.RUNNING
        val idle = status == VpnStatus.STOPPED || status == VpnStatus.FAILED
        val connecting = connectJob?.isActive == true

        when {
            connecting && idle ->
                setStatusTag(binding.vpnStatusTag, R.string.dash_tag_connecting, active = false, pending = true)
            status == VpnStatus.STOPPED -> setStatusTag(binding.vpnStatusTag, R.string.dash_tag_off, active = false)
            status == VpnStatus.FAILED -> setStatusTag(binding.vpnStatusTag, R.string.dash_tag_error, active = false)
            status == VpnStatus.STARTING ->
                setStatusTag(binding.vpnStatusTag, R.string.dash_tag_connecting, active = false, pending = true)
            status == VpnStatus.RUNNING -> setStatusTag(binding.vpnStatusTag, R.string.dash_tag_on, active = true)
            else -> setStatusTag(binding.vpnStatusTag, R.string.dash_tag_stopping, active = false, pending = true)
        }

        when {
            status == VpnStatus.FAILED ->
                showVpnError(KkmVpnService.lastError.value?.let { getString(R.string.dash_vpn_failed, it) })
            running -> showVpnError(KkmVpnService.lastError.value)
            !idle -> showVpnError(null)
        }

        binding.vpnConnectVerb.setText(if (idle && !connecting) R.string.vpn_connect else R.string.vpn_disconnect)
        binding.vpnConnectVerb.setTextColor(color(if (running) R.color.accent else R.color.text))
        DashUi.tint(binding.vpnPowerIcon, if (running) R.color.accent else R.color.muted)
        binding.vpnConnectButton.setBackgroundResource(
            if (running) R.drawable.bg_connect_ring_on else R.drawable.bg_connect_ring,
        )
        binding.vpnConnectButton.isEnabled = status != VpnStatus.STOPPING

        if (running) {
            if (vpnConnectedAt == 0L) {
                vpnConnectedAt = SystemClock.elapsedRealtime()
                vpnTrafficBaseline = uidTraffic()
            }
        } else {
            vpnConnectedAt = 0L
            lastTrafficBytes = -1L
            binding.vpnTimer.text = formatDuration(0)
            binding.vpnSpeed.setText(R.string.dash_speed_empty)
            binding.vpnTraffic.text = ""
        }
        updateVpnStats()
    }

    private fun uidTraffic(): Pair<Long, Long>? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        return rx to tx
    }

    private fun updateVpnStats() {
        if (KkmVpnService.state.value != VpnStatus.RUNNING || vpnConnectedAt == 0L) return
        binding.vpnTimer.text = formatDuration((SystemClock.elapsedRealtime() - vpnConnectedAt) / 1000)

        // sing-box живёт в нашем процессе, поэтому трафик туннеля учитывается на наш UID
        val (rx, tx) = uidTraffic() ?: return
        val total = rx + tx
        val now = SystemClock.elapsedRealtime()
        if (lastTrafficBytes >= 0 && now > lastTrafficTime) {
            val bitsPerSecond = (total - lastTrafficBytes) * 8_000.0 / (now - lastTrafficTime)
            binding.vpnSpeed.text = formatSpeed(bitsPerSecond)
        }
        vpnTrafficBaseline?.let { (baseRx, baseTx) ->
            binding.vpnTraffic.text = "↓ ${formatBytes(rx - baseRx)}  ↑ ${formatBytes(tx - baseTx)}"
        }
        lastTrafficBytes = total
        lastTrafficTime = now
    }

    // ================================================================ TgWsProxy

    private fun setupTelegram() {
        binding.tgPort.text = TelegramProxyService.DEFAULT_PORT.toString()
        binding.tgToggleButton.setOnClickListener {
            when (TelegramProxyService.state.value.status) {
                TelegramProxyStatus.STOPPED, TelegramProxyStatus.FAILED -> {
                    if (KkmVpnService.isActive) KkmVpnService.stop(this)
                    TelegramProxyService.start(this)
                }
                else -> TelegramProxyService.stop(this)
            }
        }
        binding.tgApplyButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TelegramProxyService.proxyLink(this)))
            if (intent.resolveActivity(packageManager) != null) startActivity(intent)
            else toast(R.string.telegram_not_installed)
        }
        val copyLink = View.OnClickListener {
            copyToClipboard("Telegram proxy", TelegramProxyService.proxyLink(this))
        }
        binding.tgCopyButton.setOnClickListener(copyLink)
        binding.tgLinkBox.setOnClickListener(copyLink)
        binding.tgLogsButton.setOnClickListener { LogActivity.open(this, LogActivity.Source.TGWS) }
    }

    private fun updateTelegramState(state: TelegramProxyState) {
        val running = state.status == TelegramProxyStatus.RUNNING
        val idle = state.status == TelegramProxyStatus.STOPPED || state.status == TelegramProxyStatus.FAILED

        binding.tgStatus.setText(
            when (state.status) {
                TelegramProxyStatus.STOPPED -> R.string.dash_tgws_sub
                TelegramProxyStatus.STARTING -> R.string.telegram_proxy_starting
                TelegramProxyStatus.RUNNING -> R.string.telegram_proxy_running
                TelegramProxyStatus.STOPPING -> R.string.telegram_proxy_stopping
                TelegramProxyStatus.FAILED -> R.string.telegram_proxy_failed
            },
        )
        when (state.status) {
            TelegramProxyStatus.STOPPED -> setStatusTag(binding.tgStatusTag, R.string.dash_tag_stopped, active = false)
            TelegramProxyStatus.FAILED -> setStatusTag(binding.tgStatusTag, R.string.dash_tag_error, active = false)
            TelegramProxyStatus.RUNNING -> setStatusTag(binding.tgStatusTag, R.string.dash_tag_running, active = true)
            TelegramProxyStatus.STARTING ->
                setStatusTag(binding.tgStatusTag, R.string.dash_tag_starting, active = false, pending = true)
            TelegramProxyStatus.STOPPING ->
                setStatusTag(binding.tgStatusTag, R.string.dash_tag_stopping, active = false, pending = true)
        }

        binding.tgToggleButton.setText(if (idle) R.string.telegram_proxy_start else R.string.telegram_proxy_stop)
        binding.tgToggleButton.setIconResource(if (idle) R.drawable.ic_k_play else R.drawable.ic_k_stop)
        if (idle) {
            styleButton(binding.tgToggleButton, R.color.accent, R.color.bg, R.color.accent)
        } else {
            styleButton(binding.tgToggleButton, R.color.panel_2, R.color.danger, R.color.danger_30)
        }
        binding.tgToggleButton.isEnabled = state.status != TelegramProxyStatus.STARTING &&
            state.status != TelegramProxyStatus.STOPPING

        binding.tgApplyButton.isEnabled = running
        binding.tgApplyButton.alpha = if (running) 1f else 0.6f
        if (running) {
            styleButton(binding.tgApplyButton, R.color.accent2, R.color.bg, R.color.accent2, tintIcon = false)
        } else {
            styleButton(binding.tgApplyButton, R.color.panel_2, R.color.muted, R.color.line, tintIcon = false)
        }

        binding.tgError.text = state.error
        binding.tgError.visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    private fun updateTelegramStats() {
        val stats = ProxyStats.snapshot()
        binding.tgConnections.text = "${stats.connectionsActive} / ${stats.connectionsTotal}"
        binding.tgTraffic.text = "↑ ${formatBytes(stats.bytesUp)}  ↓ ${formatBytes(stats.bytesDown)}"
    }

    // ================================================================ ByeByeDPI

    private fun setupByeDpi() {
        binding.byedpiToggleButton.setOnClickListener {
            if (appStatus.first == AppStatus.Running) {
                ServiceManager.stop(this)
            } else {
                val mode = preferences.mode()
                val permission = if (mode == Mode.VPN) VpnService.prepare(this) else null
                if (permission == null) startByeDpi(mode) else byeDpiPermission.launch(permission)
            }
        }
        binding.strategyButton.setOnClickListener {
            startActivity(Intent(this, StrategyEditorActivity::class.java))
        }
        binding.domainListsButton.setOnClickListener {
            startActivity(Intent(this, DomainListsActivity::class.java))
        }
        binding.strategyTestButton.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }
        binding.byedpiLogsButton.setOnClickListener { LogActivity.open(this, LogActivity.Source.BYEDPI) }
    }

    private fun startByeDpi(mode: Mode) {
        if (mode == Mode.VPN && KkmVpnService.isActive) KkmVpnService.stop(this)
        ServiceManager.start(this, mode)
    }

    private fun updateByeDpiStatus() {
        val (status, mode) = appStatus
        val running = status == AppStatus.Running
        binding.byedpiStatus.text = if (running) {
            getString(R.string.dash_dpi_on, if (mode == Mode.VPN) "VPN" else "Proxy")
        } else {
            getString(R.string.dash_dpi_off)
        }
        binding.byedpiToggleButton.setText(if (running) R.string.status_enabled else R.string.status_disabled)
        binding.byedpiToggleButton.setBackgroundResource(
            if (running) R.drawable.bg_power_button_on else R.drawable.bg_power_button_off,
        )
        val fg = color(if (running) R.color.accent else R.color.danger)
        binding.byedpiToggleButton.setTextColor(fg)
        TextViewCompat.setCompoundDrawableTintList(binding.byedpiToggleButton, ColorStateList.valueOf(fg))
    }

    // ================================================================ настройки

    private fun setupSettings() {
        binding.openSettingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.manageProfileButton.setOnClickListener { showProfilesSheet() }
        binding.addConfigButton.setOnClickListener { showAddSheet() }
        binding.updateProfilesButton.setOnClickListener { updateAllProfiles() }
        binding.updatesButton.setOnClickListener { startActivity(Intent(this, UpdatesActivity::class.java)) }
        binding.versionValue.text = BuildConfig.VERSION_NAME
        checkAppUpdate()
    }

    /** Раз в сутки смотрим, не вышла ли новая сборка приложения */
    private fun checkAppUpdate() {
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { Updates.checkDaily(applicationContext) } ?: return@launch
            binding.updatesBadge.visibility = View.VISIBLE
            Updates.markSeen(this@DashboardActivity, release.tag)
        }
    }

    // ================================================================ шторки

    private inner class ListSheet(val dialog: BottomSheetDialog, root: View) {
        val list: LinearLayout = root.findViewById(R.id.sheetList)
        private val actions: LinearLayout = root.findViewById(R.id.sheetActions)
        private val empty: TextView = root.findViewById(R.id.sheetEmpty)

        fun addAction(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
            val view = layoutInflater.inflate(R.layout.view_icon_button, actions, false) as ImageView
            view.setImageResource(icon)
            view.contentDescription = description
            view.setOnClickListener { onClick() }
            actions.addView(view)
        }

        fun setEmpty(text: String?) {
            empty.text = text
            empty.visibility = if (text == null) View.GONE else View.VISIBLE
        }

        fun addEntry(title: String, subtitle: String, @DrawableRes icon: Int, selected: Boolean): SheetEntry {
            val view = layoutInflater.inflate(R.layout.item_sheet_entry, list, false)
            view.findViewById<TextView>(R.id.entryTitle).text = title
            view.findViewById<TextView>(R.id.entrySubtitle).text = subtitle
            val iconView = view.findViewById<ImageView>(R.id.entryIcon)
            iconView.setImageResource(icon)
            if (selected) DashUi.tint(iconView, R.color.accent)
            view.isSelected = selected
            view.findViewById<View>(R.id.entryCheck).visibility = if (selected) View.VISIBLE else View.GONE
            list.addView(view)
            return SheetEntry(view)
        }
    }

    private class SheetEntry(val view: View) {
        val subtitle: TextView = view.findViewById(R.id.entrySubtitle)

        fun setAction(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
            view.findViewById<ImageView>(R.id.entryAction).apply {
                setImageResource(icon)
                contentDescription = description
                visibility = View.VISIBLE
                setOnClickListener { onClick() }
            }
        }

        fun setMore(onClick: () -> Unit) {
            view.findViewById<View>(R.id.entryMore).apply {
                visibility = View.VISIBLE
                setOnClickListener { onClick() }
            }
        }

        fun setEmoji(emoji: String?) {
            if (emoji.isNullOrBlank()) return
            view.findViewById<TextView>(R.id.entryEmoji).apply {
                text = emoji
                visibility = View.VISIBLE
            }
            view.findViewById<View>(R.id.entryIcon).visibility = View.GONE
        }
    }

    private fun createListSheet(title: String): ListSheet {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_list, null)
        view.findViewById<TextView>(R.id.sheetTitle).text = title
        prepareSheet(dialog, view)
        return ListSheet(dialog, view)
    }

    private fun showSheet(dialog: BottomSheetDialog, view: View) {
        prepareSheet(dialog, view)
        dialog.show()
    }

    private fun prepareSheet(dialog: BottomSheetDialog, view: View) {
        openSheet?.dismiss()
        dialog.setContentView(view)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.setBackgroundColor(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.setOnDismissListener { if (openSheet === dialog) openSheet = null }
        openSheet = dialog
    }

    // ================================================================ helpers

    private fun setStatusTag(tag: TextView, text: Int, active: Boolean, pending: Boolean = false) {
        tag.setText(text)
        tag.setBackgroundResource(
            when {
                active -> R.drawable.bg_status_tag_on
                pending -> R.drawable.bg_status_tag_warn
                else -> R.drawable.bg_status_tag_off
            },
        )
        tag.setTextColor(
            color(
                when {
                    active -> R.color.accent
                    pending -> R.color.warn
                    else -> R.color.danger
                },
            ),
        )
    }

    private fun styleButton(
        button: MaterialButton,
        @ColorRes background: Int,
        @ColorRes text: Int,
        @ColorRes stroke: Int,
        tintIcon: Boolean = true,
    ) {
        button.backgroundTintList = ColorStateList.valueOf(color(background))
        button.strokeColor = ColorStateList.valueOf(color(stroke))
        button.setTextColor(color(text))
        if (tintIcon) button.iconTint = ColorStateList.valueOf(color(text))
    }

    private fun shrinkCompoundDrawables(view: View) {
        if (view is TextView && view.compoundDrawablesRelative.any { it != null }) {
            DashUi.shrinkDrawables(view, 14)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) shrinkCompoundDrawables(view.getChildAt(i))
    }

    private fun copyToClipboard(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast(R.string.toast_copied)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(this, id)

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
        else -> "$bytes Б"
    }

    private fun formatSpeed(bitsPerSecond: Double): String = when {
        bitsPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f Мбит/с", bitsPerSecond / 1_000_000)
        else -> String.format(Locale.US, "%.0f Кбит/с", bitsPerSecond / 1_000)
    }

    private fun formatDuration(totalSeconds: Long): String = String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        totalSeconds % 3600 / 60,
        totalSeconds % 60,
    )

    companion object {
        private const val STATE_TAB = "dashboard_tab"
        private const val PREF_PROTOCOL = "dashboard_protocol"

        // Переживают пересоздание активности, пока жив процесс (и VPN-сервис)
        private var vpnConnectedAt = 0L
        private var vpnTrafficBaseline: Pair<Long, Long>? = null
        private val resolvedIps = ConcurrentHashMap<String, String>()
    }
}
