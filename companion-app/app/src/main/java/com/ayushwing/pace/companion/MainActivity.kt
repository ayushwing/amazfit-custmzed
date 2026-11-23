package com.ayushwing.pace.companion

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Status and setup screen, styled to match the watch: dark, flat, teal accent.
 * A status card at the top; below it a checklist where each unmet step has a
 * button that fixes it.
 */
class MainActivity : Activity() {
    private val ui = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var dot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var testButton: Button
    private lateinit var addressField: EditText
    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(28))
        }

        // Header: icon, name, one-line promise.
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.mipmap.ic_launcher)
            }, LinearLayout.LayoutParams(px(52), px(52)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), 0, 0, 0)
                addView(text("Pace", 26f, WHITE, medium))
                addView(text("Your Amazfit Pace, without the cloud", 14f, MUTED))
            })
        })
        space(col, 24)

        // Status card.
        val status = card(col)
        status.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            dot = View(context).apply { background = circle(MUTED) }
            addView(dot, LinearLayout.LayoutParams(px(12), px(12)))
            statusTitle = text("Not set up", 20f, WHITE, medium).apply { setPadding(px(12), 0, 0, 0) }
            addView(statusTitle)
        })
        statusDetail = text("", 14f, MUTED).apply { setPadding(0, px(6), 0, px(14)) }
        status.addView(statusDetail)
        testButton = pill("Send test notification", TEAL, BG) { sendTest() }
        status.addView(testButton, LinearLayout.LayoutParams(MATCH_PARENT, px(48)))
        space(col, 20)

        // Checklist.
        col.addView(text("SETUP", 12f, MUTED, medium).apply {
            letterSpacing = 0.12f
            setPadding(px(4), 0, 0, px(8))
        })
        val list = card(col)
        rows += Row(list, "Bluetooth permission", "Allow", { hasPermissions() }) { requestPermissions(PERMISSIONS, 1) }
        rows += Row(list, "Notification access", "Open", { notificationAccess() }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        rows += Row(list, "Unrestricted battery", "Allow", {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        rows += Row(list, "Watch address", "Save", { WatchLink.address(this) != null }, alwaysShowButton = true) {
            saveAddress()
        }
        addressField = EditText(this).apply {
            hint = "AA:BB:CC:DD:EE:FF"
            setText(WatchLink.address(this@MainActivity).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(WHITE)
            setHintTextColor(DIM)
            typeface = Typeface.MONOSPACE
            textSize = 16f
            backgroundTintList = ColorStateList.valueOf(DIM)
        }
        list.addView(addressField)
        list.addView(text("On the watch: Settings › About, or ask adb: settings get secure bluetooth_address", 12f, DIM).apply {
            setPadding(px(4), px(2), 0, px(6))
        })

        space(col, 20)
        col.addView(text("No internet permission. Notifications travel over Bluetooth to the watch and nowhere else.", 12f, DIM))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (ready()) WatchLinkService.start(this)
        watcher = ui.launch { WatchLink.state.collect(::showState) }
    }

    override fun onPause() {
        super.onPause()
        watcher?.cancel()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        refresh()
        if (ready()) WatchLinkService.start(this)
    }

    private fun refresh() = rows.forEach { it.update() }

    private fun ready() = hasPermissions() && WatchLink.address(this) != null

    private fun showState(s: WatchLink.State) {
        val (color, title, detail) = when (s) {
            WatchLink.State.NotSetUp -> Triple(MUTED, "Not set up", "Finish the steps below to connect.")
            is WatchLink.State.Connecting -> Triple(AMBER, "Connecting…", "Looking for the watch over Bluetooth.")
            is WatchLink.State.Connected -> Triple(
                TEAL, "Connected",
                s.model + if (s.battery >= 0) "  ·  battery ${s.battery}%" else "",
            )
            is WatchLink.State.Waiting -> Triple(CORAL, "Watch not reachable", "Retrying in ${s.retryInSec}s. Keep the watch nearby with Bluetooth on.")
        }
        dot.background = circle(color)
        statusTitle.text = title
        statusDetail.text = detail
        val connected = s is WatchLink.State.Connected
        testButton.isEnabled = connected
        testButton.alpha = if (connected) 1f else 0.4f
    }

    private fun saveAddress() {
        val a = addressField.text.toString().trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(a)) {
            addressField.error = "Use the form AA:BB:CC:DD:EE:FF"
            return
        }
        WatchLink.setAddress(this, a)
        refresh()
        if (ready()) WatchLinkService.start(this)
    }

    private fun sendTest() {
        val o = JSONObject()
            .put("key", "pace-test-${System.currentTimeMillis()}")
            .put("app", "Pace")
            .put("title", "Hello from your phone")
            .put("text", "If you can read this on the watch, the Bluetooth link works.")
            .put("when", System.currentTimeMillis())
        if (!WatchLink.send(Frame(Frame.NOTIF_PUSH, o.toString()))) statusDetail.text = "Not connected"
    }

    private fun hasPermissions() = PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun notificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(ComponentName(this, NotificationMirror::class.java).flattenToString())
    }

    // ------------------------------------------------------------- view kit

    private fun px(v: Int) = (v * dp).toInt()

    private fun text(s: String, size: Float, color: Int, face: Typeface = regular) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = face
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(18), px(18), px(18))
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = 20 * dp
        }
        parent.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun pill(label: String, fill: Int, ink: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        typeface = medium
        setTextColor(ink)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 24 * dp
        }
        setOnClickListener { onClick() }
    }

    private fun space(parent: LinearLayout, h: Int) =
        parent.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, px(h)))

    /** One checklist line: a tick or empty ring, the label, and a fix-it button. */
    private inner class Row(
        parent: LinearLayout,
        label: String,
        action: String,
        private val isDone: () -> Boolean,
        private val alwaysShowButton: Boolean = false,
        onFix: () -> Unit,
    ) {
        private val mark = TextView(this@MainActivity).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = medium
        }
        private val button = pill(action, LINE, WHITE, onFix)

        init {
            parent.addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(8), 0, px(8))
                addView(mark, LinearLayout.LayoutParams(px(24), px(24)))
                addView(text(label, 16f, WHITE).apply { setPadding(px(12), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(button, LinearLayout.LayoutParams(WRAP_CONTENT, px(40)))
            })
        }

        fun update() {
            val ok = isDone()
            mark.text = if (ok) "✓" else ""
            mark.setTextColor(BG)
            mark.background = if (ok) circle(TEAL) else GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(px(2), DIM)
            }
            button.visibility = if (ok && !alwaysShowButton) View.GONE else View.VISIBLE
        }
    }

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    companion object {
        private val BG = Color.parseColor("#0E0E0D")
        private val CARD = Color.parseColor("#1C1C1A")
        private val LINE = Color.parseColor("#2E2E2C")
        private val WHITE = Color.parseColor("#FFFFFF")
        private val MUTED = Color.parseColor("#9C9A92")
        private val DIM = Color.parseColor("#5F5E5A")
        private val TEAL = Color.parseColor("#5DCAA5")
        private val AMBER = Color.parseColor("#EF9F27")
        private val CORAL = Color.parseColor("#F0997B")

        private val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
