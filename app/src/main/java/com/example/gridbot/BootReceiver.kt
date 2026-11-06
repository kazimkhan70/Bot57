package com.example.gridbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the bot after a reboot if it was running when the phone shut down. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Prefs.plain(c).getBoolean("autostart", false)) {
            try { c.startForegroundService(Intent(c, BotService::class.java)) } catch (_: Exception) {}
        }
    }
}
