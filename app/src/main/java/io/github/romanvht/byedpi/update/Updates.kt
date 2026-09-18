package io.github.romanvht.byedpi.update

import android.content.Context
import androidx.core.content.edit
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.utility.getPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Релиз на GitHub. */
data class Release(
    val tag: String,
    val name: String,
    val notes: String,
    val apkUrl: String?,
    val apkSize: Long,
    val pageUrl: String,
)

/** Встроенный компонент и его оригинальный репозиторий. */
data class Component(
    val title: String,
    val repo: String,
    val bundled: String,
    val comment: String,
)

object Updates {
    const val PREF_REPO = "update_repo"
    private const val PREF_LAST_CHECK = "update_last_check"
    private const val PREF_LAST_SEEN = "update_last_seen_tag"

    /** Репозиторий с релизами KKMProxy; в приложении его можно заменить своим */
    const val DEFAULT_REPO = "kukmber/KKMProxy"

    fun repo(context: Context): String =
        context.getPreferences().getString(PREF_REPO, null)?.trim()?.ifBlank { null } ?: DEFAULT_REPO

    /** Пустая строка возвращает репозиторий по умолчанию */
    fun setRepo(context: Context, repo: String) {
        context.getPreferences().edit { putString(PREF_REPO, repo.trim().trim('/')) }
    }

    /** Из чего собрано приложение — сверяется с оригиналами по кнопке «Проверить». */
    val components = listOf(
        Component(
            title = "byebyeDPI",
            repo = "romanvht/ByeByeDPI",
            bundled = "v1.7.8",
            comment = "приложение собрано на его основе",
        ),
        Component(
            title = "Ядро ByeDPI",
            repo = "hufrea/byedpi",
            bundled = BuildConfig.BYEDPI_VERSION,
            comment = "нативное ядро обхода DPI",
        ),
        Component(
            title = "TgWsProxy",
            repo = "Flowseal/tg-ws-proxy",
            bundled = BuildConfig.TGWS_VERSION,
            comment = "порт оригинала на Kotlin",
        ),
        Component(
            title = "Ядро VPN (mihomo)",
            repo = "MetaCubeX/mihomo",
            bundled = BuildConfig.MIHOMO_VERSION,
            comment = "то же ядро, что во FlClashX",
        ),
        Component(
            title = "hev-socks5-tunnel",
            repo = "heiher/hev-socks5-tunnel",
            bundled = "2.17.1",
            comment = "передаёт трафик VPN в ядро",
        ),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Последний релиз репозитория, null — если релизов нет или сеть недоступна. Не в главном потоке. */
    fun latestRelease(repo: String): Release? {
        if (repo.isBlank()) return null
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KKMProxy")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        // Универсальный APK подходит любому телефону
                        if (!name.endsWith(".apk", ignoreCase = true)) continue
                        if (apkUrl == null || name.contains("universal", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            apkSize = asset.optLong("size")
                        }
                    }
                }
                Release(
                    tag = json.optString("tag_name"),
                    name = json.optString("name").ifBlank { json.optString("tag_name") },
                    notes = json.optString("body"),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    pageUrl = json.optString("html_url").ifBlank { "https://github.com/$repo/releases" },
                )
            }
        }.getOrNull()
    }

    /** Версия приложения без суффикса сборки */
    fun currentVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** true, если [candidate] новее [current]; версии сравниваются по числам */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String) =
        Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()

    /** Фоновая проверка не чаще раза в сутки; возвращает более новый релиз приложения. */
    fun checkDaily(context: Context): Release? {
        val prefs = context.getPreferences()
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0) < 24 * 60 * 60 * 1000) return null
        val release = latestRelease(repo(context)) ?: return null
        prefs.edit { putLong(PREF_LAST_CHECK, now) }
        return release.takeIf { isNewer(it.tag, currentVersion()) }
    }

    fun markSeen(context: Context, tag: String) {
        context.getPreferences().edit { putString(PREF_LAST_SEEN, tag) }
    }

    fun lastSeen(context: Context): String? =
        context.getPreferences().getString(PREF_LAST_SEEN, null)
}
