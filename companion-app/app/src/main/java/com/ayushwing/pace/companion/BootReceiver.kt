package com.ayushwing.pace.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects to the watch after the phone restarts, once it has been set up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && WatchLink.address(c) != null) {
            WatchLinkService.start(c)
        }
    }
}
