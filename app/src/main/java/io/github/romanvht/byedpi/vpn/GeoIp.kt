package io.github.romanvht.byedpi.vpn

import android.content.Context
import androidx.core.content.edit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Определение страны сервера по его IP (с кэшем в настройках). */
object GeoIp {
    private const val PREFS = "geoip_cache"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun cached(context: Context, ip: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ip, null)

    /** Возвращает ISO-код страны ("NL") или null. Выполнять не в главном потоке. */
    fun lookup(context: Context, ip: String): String? {
        cached(context, ip)?.let { return it }
        val code = query("https://api.country.is/$ip") { it.optString("country") }
            ?: query("https://ipwho.is/$ip") { json ->
                json.optString("country_code").takeIf { json.optBoolean("success", true) }
            }
            ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(ip, code) }
        return code
    }

    private fun query(url: String, extract: (JSONObject) -> String?): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", "KKMProxy").build()).execute().use {
            if (!it.isSuccessful) return@use null
            extract(JSONObject(it.body?.string().orEmpty()))
                ?.uppercase()
                ?.takeIf { code -> code.length == 2 }
        }
    }.getOrNull()
}
