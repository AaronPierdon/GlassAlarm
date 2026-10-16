package com.aaron.glassalarm

import android.app.Application
import com.aaron.glassalarm.alarm.CalendarChangeJob
import com.aaron.glassalarm.alarm.Notifications
import com.aaron.glassalarm.alarm.Scheduler
import com.aaron.glassalarm.alarm.WatcherService

class GlassAlarmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        CalendarChangeJob.schedule(this)
        Scheduler.rescheduleAsync(this, "app-start")
        WatcherService.ensureRunning(this)
    }
}
