package com.ayushwing.pace.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the Bluetooth link after boot, before the launcher is first shown. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        LinkService.start(c);
    }
}
