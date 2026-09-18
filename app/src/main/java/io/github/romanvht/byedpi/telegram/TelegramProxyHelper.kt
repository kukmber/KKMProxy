package io.github.romanvht.byedpi.telegram

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Генерирует ссылку tg://socks?... на основе локального SOCKS5-прокси,
 * который уже поднимает ByeDPI (тот же порт, что используется VPN/Proxy-режимом),
 * и открывает её в установленном клиенте Telegram (включая форки: AyuGram,
 * Nekogram, Kotatogram и т.д. — они тоже обрабатывают tg://socks).
 *
 * Предполагается, что ByeDPI уже слушает локальный SOCKS5 на 127.0.0.1:<port>.
 * Порт нужно взять из текущей конфигурации приложения (см. AppConfig/Prefs).
 */
object TelegramProxyHelper {

    /**
     * @param host обычно "127.0.0.1", т.к. прокси локальный
     * @param port порт локального SOCKS5, на котором слушает ByeDPI
     * @param username опционально, если прокси требует авторизацию (для локального — обычно не нужно)
     * @param password опционально
     */
    fun buildProxyUri(
        host: String = "127.0.0.1",
        port: Int,
        username: String? = null,
        password: String? = null
    ): Uri {
        val builder = Uri.Builder()
            .scheme("tg")
            .authority("socks")
            .appendQueryParameter("server", host)
            .appendQueryParameter("port", port.toString())

        if (!username.isNullOrEmpty()) {
            builder.appendQueryParameter("user", username)
        }
        if (!password.isNullOrEmpty()) {
            builder.appendQueryParameter("pass", password)
        }

        return builder.build()
    }

    /**
     * Открывает сгенерированную ссылку в Telegram.
     * Возвращает false, если ни один клиент Telegram не установлен
     * (Intent.resolveActivity вернёт null).
     */
    fun openInTelegram(
        context: Context,
        port: Int,
        host: String = "127.0.0.1",
        username: String? = null,
        password: String? = null
    ): Boolean {
        val uri = buildProxyUri(host, port, username, password)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }
}

/*
 * Использование в UI (например, в главном фрагменте/composable рядом с общим тогглом):
 *
 * val portFromConfig = ByeDpiConfig.getSocksPort(context) // текущий порт из настроек ByeDPI
 * val opened = TelegramProxyHelper.openInTelegram(context, port = portFromConfig)
 * if (!opened) {
 *     // показать снекбар/тост: "Telegram не найден на устройстве"
 * }
 *
 * Важно: кнопку "Открыть в Telegram" стоит делать активной только когда
 * сервис ByeDPI реально запущен (проверять состояние VpnService/статус в UI-стейте),
 * иначе ссылка укажет на порт, на котором никто не слушает.
 */
