package io.github.romanvht.byedpi.telegram.proxy

import android.util.Log

internal object DiagnosticLogger {
    private const val TAG = "TelegramProxy"

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, format(name, fields))
    }

    fun failure(name: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, format(name, fields), error)
    }

    private fun format(name: String, fields: Array<out Pair<String, Any?>>): String =
        fields.joinToString(prefix = "$name ", separator = " ") { (key, value) -> "$key=$value" }.trimEnd()
}
