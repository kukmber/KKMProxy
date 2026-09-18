package io.github.romanvht.byedpi.vpn

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.utility.getPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** VPN-профиль: подписка по ссылке, импортированный файл или одиночная ссылка на узел. */
data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: String,
    val url: String? = null,
    val content: String = "",
    val updatedAt: Long = 0L,
    val userInfo: String? = null,
    val selectedNodes: Map<String, String> = emptyMap(),
) {
    val nodes: List<VpnConfigParser.VpnNode>
        get() = runCatching { VpnConfigParser.parse(content) }.getOrDefault(emptyList())

    val canUpdate: Boolean get() = source == SOURCE_URL && !url.isNullOrBlank()

    /** "12.3 / 100 ГБ · до 01.10.2026" из заголовка subscription-userinfo */
    fun trafficSummary(): String? {
        val info = userInfo ?: return null
        val values = info.split(';').associate {
            it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim().toLongOrNull()
        }
        val used = (values["upload"] ?: 0L) + (values["download"] ?: 0L)
        val total = values["total"] ?: 0L
        val expire = values["expire"] ?: 0L
        val parts = mutableListOf<String>()
        if (total > 0) parts += "${gb(used)} / ${gb(total)} ГБ"
        else if (used > 0) parts += "${gb(used)} ГБ"
        if (expire > 0) {
            parts += "до " + SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(expire * 1000))
        }
        return parts.joinToString(" · ").ifBlank { null }
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)

    companion object {
        const val SOURCE_URL = "url"
        const val SOURCE_FILE = "file"
        const val SOURCE_LINK = "link"
    }
}

object ProfileStore {
    private const val FILE_NAME = "vpn_profiles.json"
    private const val PREF_SELECTED = "vpn_selected_profile"

    // Ключи старого дашборда — для однократного переноса
    private const val OLD_NAME = "dashboard_profile_name"
    private const val OLD_URL = "dashboard_subscription_url"
    private const val OLD_USER = "dashboard_auth_user"
    private const val OLD_PASSWORD = "dashboard_auth_password"
    private const val OLD_YAML = "dashboard_imported_yaml"

    private val gson = Gson()
    private val lock = Any()

    fun all(context: Context): List<VpnProfile> = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return migrate(context)
        runCatching {
            val type = object : TypeToken<List<VpnProfile>>() {}.type
            gson.fromJson<List<VpnProfile>>(file.readText(), type)
        }.getOrNull()
            ?.map { it.copy(selectedNodes = it.selectedNodes ?: emptyMap()) }
            ?: emptyList()
    }

    fun get(context: Context, id: String): VpnProfile? = all(context).firstOrNull { it.id == id }

    fun selected(context: Context): VpnProfile? {
        val profiles = all(context)
        val id = context.getPreferences().getString(PREF_SELECTED, null)
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    fun select(context: Context, id: String) {
        context.getPreferences().edit { putString(PREF_SELECTED, id) }
    }

    fun upsert(context: Context, profile: VpnProfile) = synchronized(lock) {
        val profiles = all(context).toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles += profile
        save(context, profiles)
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        save(context, all(context).filterNot { it.id == id })
    }

    fun selectNode(context: Context, profileId: String, protocol: String, nodeName: String) {
        val profile = get(context, profileId) ?: return
        upsert(context, profile.copy(selectedNodes = profile.selectedNodes + (protocol to nodeName)))
    }

    /** Скачивает подписку заново и сохраняет. Выполнять не в главном потоке. */
    fun refresh(context: Context, profile: VpnProfile): VpnProfile {
        val url = profile.url ?: return profile
        val subscription = VpnConfigParser.download(url)
        if (VpnConfigParser.parse(subscription.content).isEmpty()) {
            throw IllegalStateException("В подписке не найдено ни одного узла")
        }
        val updated = profile.copy(
            content = subscription.content,
            updatedAt = System.currentTimeMillis(),
            userInfo = subscription.userInfo,
        )
        upsert(context, updated)
        return updated
    }

    /** Создаёт профиль из ссылки на подписку и сразу скачивает её. */
    fun addSubscription(context: Context, url: String, name: String?): VpnProfile {
        val subscription = VpnConfigParser.download(url)
        if (VpnConfigParser.parse(subscription.content).isEmpty()) {
            throw IllegalStateException("В подписке не найдено ни одного узла")
        }
        val profile = VpnProfile(
            name = name?.takeIf { it.isNotBlank() }
                ?: subscription.title
                ?: runCatching { java.net.URI(url.trim()).host }.getOrNull()
                ?: "Подписка",
            source = VpnProfile.SOURCE_URL,
            url = url.trim(),
            content = subscription.content,
            updatedAt = System.currentTimeMillis(),
            userInfo = subscription.userInfo,
        )
        upsert(context, profile)
        select(context, profile.id)
        return profile
    }

    /** Профиль из содержимого (файл, QR с vless://, буфер обмена). */
    fun addContent(context: Context, content: String, name: String, source: String): VpnProfile {
        val nodes = VpnConfigParser.parse(content)
        if (nodes.isEmpty()) throw IllegalStateException("Не найдено ни одного узла")
        val profile = VpnProfile(
            name = name.ifBlank { nodes.first().name },
            source = source,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        upsert(context, profile)
        select(context, profile.id)
        return profile
    }

    private fun save(context: Context, profiles: List<VpnProfile>) {
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(gson.toJson(profiles))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun migrate(context: Context): List<VpnProfile> {
        val prefs = context.getPreferences()
        val profiles = mutableListOf<VpnProfile>()

        prefs.getString(OLD_YAML, null)?.takeIf { it.isNotBlank() }?.let { yaml ->
            profiles += VpnProfile(
                name = "Импортированный файл",
                source = VpnProfile.SOURCE_FILE,
                content = yaml,
                updatedAt = System.currentTimeMillis(),
            )
        }

        // Подписка переносится только та, что пользователь уже добавил сам
        prefs.getString(OLD_URL, null)?.takeIf { it.isNotBlank() }?.let { oldUrl ->
            val user = prefs.getString(OLD_USER, null)
            val password = prefs.getString(OLD_PASSWORD, null)
            // Старые логин/пароль переносятся прямо в ссылку (https://user:pass@host/...)
            val url = if (!user.isNullOrBlank() && !oldUrl.contains('@')) {
                oldUrl.replaceFirst("://", "://${enc(user)}:${enc(password.orEmpty())}@")
            } else {
                oldUrl
            }
            profiles += VpnProfile(
                name = prefs.getString(OLD_NAME, null)?.takeIf { it.isNotBlank() } ?: "Основной профиль",
                source = VpnProfile.SOURCE_URL,
                url = url,
            )
        }

        save(context, profiles)
        prefs.edit {
            remove(OLD_YAML)
            remove(OLD_URL)
            remove(OLD_USER)
            remove(OLD_PASSWORD)
            remove(OLD_NAME)
            profiles.firstOrNull()?.let { putString(PREF_SELECTED, it.id) }
        }
        return profiles
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
