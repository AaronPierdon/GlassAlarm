# Glass Alarm — calendar alarms that actually wake a locked OnePlus 12

A personal Android app (Kotlin + Jetpack Compose) that reads your calendars, lets you pick accounts and calendars, and turns events into full-screen glass alarms over the lock screen. It's built to keep working under OxygenOS's aggressive background killing.

## Build & install (Windows)

1. Install **Android Studio** (any 2024+ version).
2. **File → Open** this folder. Let Gradle sync. If Studio offers an AGP or Gradle upgrade, accepting it is fine.
3. On the phone, turn on **Developer options → USB debugging**, then plug the phone in.
4. Press **Run ▶** (pick your OnePlus as the device). You can also use **Build → Build APK(s)** and sideload `app/build/outputs/apk/debug/app-debug.apk`.

## First run

1. The app opens on **Setup**. Tap **Grant everything**. Each settings page opens in turn: flip the switch, press back, and the next one opens.
2. Work through the **OnePlus 12 checklist** on the same screen. The most important item is **locking the app in Recents**.
3. Tap **Test in 15 s**, **lock the phone**, and put it down. The glass alarm should light the screen over the lock screen.
4. If it doesn't, the **Diagnostics** log at the bottom of Setup shows how far it got (fired → service → launch requested → "alarm screen shown").

## How it wakes the lock screen (and why the other app may not)

The alarm goes through several redundant layers, so OxygenOS has to break all of them to stop it:

| Layer | What it does |
|---|---|
| `AlarmManager.setAlarmClock()` | The highest-priority alarm type. It is exempt from Doze, fires on the second, and gives the app a short window to start a foreground service and an activity. |
| Foreground service (`mediaPlayback`) | Owns the ringing. It plays sound on the **ALARM** stream, which ignores silent mode. |
| Full-screen-intent notification (`CATEGORY_ALARM`) | The official Android path to the lock screen. |
| Direct `startActivity()` | Allowed from the background because the app holds **Display over other apps**. This is the main OnePlus workaround. It is retried at 1.5 s and 4 s in case the keyguard swallowed the first launch. |
| `FULL_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP` | Physically turns the panel on. On Android 14+ this needs the **Turn screen on** special access. Check whether *Calendar Alarm* has that access too; it is the last entry in your Special app access screenshot. |
| `showWhenLocked` + `turnScreenOn` | Set in the manifest, in code, and as legacy window flags. |
| `EVENT_REMINDER` broadcast | A second trigger path that fires off Google Calendar's own reminder. |

**About "the event needs a reminder":** the other app listens only for the calendar's own reminder broadcast, so events without a reminder never trigger it. This app reads events directly, so a reminder is **optional**. In *Settings → When to ring* you can choose **At event start**, **At reminder times** (with fallback to the start time), or **Both**.

## Picking up new events in seconds

* A **ContentObserver** in an always-on watcher service rescans about 1 s after any calendar change.
* A **JobScheduler content trigger** does the same even if the process was killed.
* **Screen-on and unlock** trigger a server sync nudge, which catches events you added on the web.
* A periodic heartbeat runs every 2, 15 or 30 minutes depending on the **Background engine** mode. When the screen is off and nothing is due within 2 hours, it slows to 4× longer ("internal sleep").
* A health check posts a notification if OnePlus reverts battery optimization or any other critical permission.

## Android Auto: no alarms while driving

The app checks **one signal**: Android Auto's own live connection state. This is the official Car App Library `CarConnection` value, read from the `androidx.car.app.connection` provider that the Android Auto app publishes. A value of `2` means projecting, which happens only while Android Auto is running on the car screen. The app reads it fresh at the moment each alarm fires and never caches it. If it can't read the value, it treats you as not driving, so a broken check can never quietly swallow your alarms.

* **Hold until I disconnect** (default): no screen and no sound. You get a silent "held" notification, and the alarm rings once Android Auto disconnects, unless the event is already over. Holds also expire after 4 hours.
* **Silent notification only**: the alarm never rings for that event.
* **Ring anyway**: Android Auto is ignored.
* If Android Auto connects **while an alarm is already ringing**, the alarm stops within about 5 seconds.
* **Setup → Android Auto detection** shows the live state, refreshed every 3 s. Check it once in the car.

## Per-event control

On the **Upcoming** tab, each event has a button that cycles **Alarm → Notify → Off**. New events always default to **Alarm**.

## Project map

```
app/src/main/java/com/aaron/glassalarm/
  data/      Prefs, CalendarRepo (calendars/instances/reminders), Weather (Open-Meteo), EventLog
  alarm/     Scheduler, AlarmReceiver/SystemEventReceiver, AlarmRingService, AlarmActivity,
             WatcherService + CalendarChangeJob, Notifications, PermissionCenter/HealthCheck/OemHelper
  ui/        MainActivity (tabs), Upcoming/Calendars/Settings/Setup screens, AlarmScreen, Glass theme
```
