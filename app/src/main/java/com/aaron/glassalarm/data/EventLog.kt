package com.aaron.glassalarm.data

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny on-device diagnostics log (last 150 lines) shown on the Setup screen. */
object EventLog {
    private const val MAX = 150
    private const val KEY = "eventlog"
    private val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        Log.i("GlassAlarm", msg)
        val sp = Prefs(ctx).sp
        val lines = (sp.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()
        lines.add(0, "${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeAt(lines.size - 1)
        sp.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun lines(ctx: Context): List<String> =
        (Prefs(ctx).sp.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }

    fun clear(ctx: Context) = Prefs(ctx).sp.edit().remove(KEY).apply()
}
