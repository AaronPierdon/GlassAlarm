package com.aaron.glassalarm.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

enum class TriggerMode(val label: String) {
    START("At event start"),
    REMINDERS("At reminder times"),
    BOTH("Reminders + event start"),
}

/**
 * How hard the app works to notice calendar changes.
 * heartbeatMin = periodic rescan/health-check; syncMin = how often we ask the account to pull from the server.
 */
enum class PerfMode(val label: String, val detail: String, val heartbeatMin: Int, val syncMin: Int, val persistentWatcher: Boolean) {
    AGGRESSIVE("Aggressive", "Always-on watcher, rescan every 2 min, server sync every 3 min", 2, 3, true),
    BALANCED("Balanced", "Always-on watcher, rescan every 15 min, server sync every 15 min", 15, 15, true),
    LIGHT("Light", "No persistent service; relies on change-trigger jobs + 30 min rescans", 30, 60, false),
}

enum class AlertMode { ALARM, NOTIFY, OFF;
    fun next(): AlertMode = when (this) { ALARM -> NOTIFY; NOTIFY -> OFF; OFF -> ALARM }
}

/** What to do with an alarm that comes due while Android Auto is projecting. */
enum class CarBehavior(val label: String, val detail: String) {
    HOLD("Hold until I disconnect", "Silent note now; rings as soon as Android Auto disconnects (if the event isn't over)."),
    SILENT("Silent notification only", "No screen, no sound while driving. You just get a silent notification."),
    RING("Ring anyway", "Ignore Android Auto."),
}

enum class DismissStyle(val label: String) { SLIDE("Slide to dismiss"), BUTTONS("Big buttons") }

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("glass_alarm", Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumOf(key: String, def: T): T =
        sp.getString(key, null)?.let { s -> runCatching { enumValueOf<T>(s) }.getOrNull() } ?: def

    private fun putEnum(key: String, v: Enum<*>) = sp.edit().putString(key, v.name).apply()

    // ---------- Timing ----------
    var triggerMode: TriggerMode
        get() = enumOf("triggerMode", TriggerMode.REMINDERS)
        set(v) = putEnum("triggerMode", v)

    /** In REMINDERS mode, an event without any reminder still alarms (at start minus defaultLeadMinutes). */
    var fallbackWhenNoReminder: Boolean
        get() = sp.getBoolean("fallbackNoRem", true)
        set(v) = sp.edit().putBoolean("fallbackNoRem", v).apply()

    var defaultLeadMinutes: Int
        get() = sp.getInt("defaultLead", 0)
        set(v) = sp.edit().putInt("defaultLead", v.coerceIn(0, 24 * 60)).apply()

    var includeAllDay: Boolean
        get() = sp.getBoolean("allDay", false)
        set(v) = sp.edit().putBoolean("allDay", v).apply()

    var allDayMinuteOfDay: Int
        get() = sp.getInt("allDayMin", 8 * 60)
        set(v) = sp.edit().putInt("allDayMin", ((v % 1440) + 1440) % 1440).apply()

    var skipDeclined: Boolean
        get() = sp.getBoolean("skipDeclined", true)
        set(v) = sp.edit().putBoolean("skipDeclined", v).apply()

    var lookaheadDays: Int
        get() = sp.getInt("lookahead", 7)
        set(v) = sp.edit().putInt("lookahead", v.coerceIn(1, 60)).apply()

    /** If the phone was off/asleep and we're late, still ring if we're no more than this many minutes late. */
    var maxLateMinutes: Int
        get() = sp.getInt("maxLate", 15)
        set(v) = sp.edit().putInt("maxLate", v).apply()

    // ---------- Ringing ----------
    var snoozeMinutes: Int
        get() = sp.getInt("snooze", 5)
        set(v) = sp.edit().putInt("snooze", v.coerceIn(1, 120)).apply()

    var autoSilenceMinutes: Int
        get() = sp.getInt("autoSilence", 10)
        set(v) = sp.edit().putInt("autoSilence", v.coerceIn(1, 60)).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var crescendo: Boolean
        get() = sp.getBoolean("crescendo", true)
        set(v) = sp.edit().putBoolean("crescendo", v).apply()

    var ringtoneUri: String?
        get() = sp.getString("ringtone", null)
        set(v) = sp.edit().putString("ringtone", v).apply()

    // ---------- Look ----------
    var themeId: String
        get() = sp.getString("theme", "auto") ?: "auto"
        set(v) = sp.edit().putString("theme", v).apply()

    var showWeather: Boolean
        get() = sp.getBoolean("showWeather", true)
        set(v) = sp.edit().putBoolean("showWeather", v).apply()

    var showLocalInfo: Boolean
        get() = sp.getBoolean("showLocal", true)
        set(v) = sp.edit().putBoolean("showLocal", v).apply()

    var showNextEvents: Boolean
        get() = sp.getBoolean("showNext", true)
        set(v) = sp.edit().putBoolean("showNext", v).apply()

    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", true)
        set(v) = sp.edit().putBoolean("fahrenheit", v).apply()

    var clock24h: Boolean
        get() = sp.getBoolean("clock24", false)
        set(v) = sp.edit().putBoolean("clock24", v).apply()

    var dismissStyle: DismissStyle
        get() = enumOf("dismissStyle", DismissStyle.SLIDE)
        set(v) = putEnum("dismissStyle", v)

    var weatherCity: String
        get() = sp.getString("weatherCity", "") ?: ""
        set(v) = sp.edit().putString("weatherCity", v).remove("cityLat").remove("cityLon").apply()

    var cityLat: Double?
        get() = sp.getString("cityLat", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("cityLat", v?.toString()).apply()

    var cityLon: Double?
        get() = sp.getString("cityLon", null)?.toDoubleOrNull()
        set(v) = sp.edit().putString("cityLon", v?.toString()).apply()

    var weatherCache: String?
        get() = sp.getString("weatherCache", null)
        set(v) = sp.edit().putString("weatherCache", v).apply()

    // ---------- Engine ----------
    var perfMode: PerfMode
        get() = enumOf("perfMode", PerfMode.AGGRESSIVE)
        set(v) = putEnum("perfMode", v)

    var carBehavior: CarBehavior
        get() = enumOf("carBehavior", CarBehavior.HOLD)
        set(v) = putEnum("carBehavior", v)

    // ---------- Held-while-driving alarms ----------
    fun holds(): List<RingingAlarm> = RingingAlarm.listFromJson(sp.getString("holds", null))

    @Synchronized
    fun addHolds(list: List<RingingAlarm>) {
        val now = System.currentTimeMillis()
        val started = readMap("holdStart")
        editMap("holdStart") { list.forEach { if (!started.containsKey(it.key)) put(it.key, now) } }
        setHolds((holds() + list).distinctBy { it.key })
    }

    fun setHolds(list: List<RingingAlarm>) {
        sp.edit().putString("holds", RingingAlarm.listToJson(list)).commit()
    }

    fun clearHolds() {
        sp.edit().remove("holds").remove("holdStart").commit()
    }

    fun holdStartedAt(key: String): Long = readMap("holdStart")[key] ?: System.currentTimeMillis()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var nextAlarmAt: Long
        get() = sp.getLong("nextAlarmAt", 0L)
        set(v) = sp.edit().putLong("nextAlarmAt", v).apply()

    var nextAlarmTitle: String
        get() = sp.getString("nextAlarmTitle", "") ?: ""
        set(v) = sp.edit().putString("nextAlarmTitle", v).apply()

    var lastRescanAt: Long
        get() = sp.getLong("lastRescan", 0L)
        set(v) = sp.edit().putLong("lastRescan", v).apply()

    var lastSyncRequestAt: Long
        get() = sp.getLong("lastSyncReq", 0L)
        set(v) = sp.edit().putLong("lastSyncReq", v).apply()

    /** JSON array of the alarms currently ringing (survives process death so the alarm screen can recover). */
    var ringingJson: String?
        get() = sp.getString("ringing", null)
        set(v) = sp.edit().putString("ringing", v).commit().let { }

    // ---------- Sets / maps stored as JSON (StringSet from SharedPreferences is mutable-unsafe) ----------
    fun disabledCalendars(): Set<Long> = readMap("disabledCals").keys.mapNotNull { it.toLongOrNull() }.toSet()
    fun setCalendarEnabled(id: Long, enabled: Boolean) = editMap("disabledCals") {
        if (enabled) remove(id.toString()) else put(id.toString(), 1L)
    }

    /** Per-instance alert override; key = "eventId@begin". Absent = ALARM (always on by default). */
    fun instanceMode(instanceKey: String): AlertMode {
        val v = readMap("modes")[instanceKey] ?: return AlertMode.ALARM
        return AlertMode.entries.getOrElse(v.toInt()) { AlertMode.ALARM }
    }
    fun allInstanceModes(): Map<String, Long> = readMap("modes")
    fun setInstanceMode(instanceKey: String, mode: AlertMode) = editMap("modes") {
        if (mode == AlertMode.ALARM) remove(instanceKey) else put(instanceKey, mode.ordinal.toLong())
    }

    /** trigger key -> time it fired (prevents double-firing on reschedule). */
    fun firedMap(): Map<String, Long> = readMap("fired")
    fun markFired(keys: Collection<String>, at: Long) = editMap("fired") { keys.forEach { put(it, at) } }

    /** trigger key -> snoozed-until time. */
    fun snoozeMap(): Map<String, Long> = readMap("snoozes")
    fun snooze(keys: Collection<String>, until: Long) {
        editMap("snoozes") { keys.forEach { put(it, until) } }
    }
    fun clearSnoozes(keys: Collection<String>) = editMap("snoozes") { keys.forEach { remove(it) } }

    fun prune(now: Long) {
        val dayAgo = now - 36 * 3600_000L
        editMap("fired") { entries.removeAll { it.value < dayAgo } }
        editMap("snoozes") { entries.removeAll { it.value < dayAgo } }
        editMap("modes") {
            entries.removeAll { e -> e.key.substringAfter('@').toLongOrNull()?.let { it < now - 7 * 86400_000L } ?: true }
        }
    }

    @Synchronized
    private fun readMap(key: String): Map<String, Long> {
        val raw = sp.getString(key, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getLong(it) }
        }.getOrDefault(emptyMap())
    }

    @Synchronized
    private fun editMap(key: String, block: MutableMap<String, Long>.() -> Unit) {
        val m = readMap(key).toMutableMap()
        m.block()
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        sp.edit().putString(key, o.toString()).commit()
    }
}
