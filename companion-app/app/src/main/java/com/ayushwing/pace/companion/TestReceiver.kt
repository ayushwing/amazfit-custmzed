package com.ayushwing.pace.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Test hook for test-e2e.sh. Guarded by android.permission.DUMP, which only the
 * adb shell and the system hold, so other apps cannot trigger it.
 *
 *   adb shell am broadcast -n com.ayushwing.pace.companion/.TestReceiver -a com.ayushwing.pace.companion.TEST_CALL
 *
 * Rings the watch with a fake call; Answer/Decline come back to
 * NotificationMirror.onCallAction, which ends the fake call.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != ACTION_TEST_CALL) return
        WatchLink.send(Frame(Frame.CALL_START, JSONObject()
            .put("key", NotificationMirror.TEST_CALL_KEY)
            .put("caller", "Test caller")
            .put("app", "Phone")
            .toString()))
    }

    companion object {
        const val ACTION_TEST_CALL = "com.ayushwing.pace.companion.TEST_CALL"
    }
}
