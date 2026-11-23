package com.ayushwing.pace.companion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataOutputStream
import java.util.UUID

/** Shared state between the link service, the notification mirror and the UI. */
object WatchLink {
    /** Must match LinkService.SERVICE_UUID on the watch. */
    val SERVICE_UUID: UUID = UUID.fromString("6b1d6a1e-6f8e-4c55-9f0a-5ac0c9a7e001")

    sealed interface State {
        data object NotSetUp : State
        data class Connecting(val attempt: Int) : State
        data class Connected(val model: String, val battery: Int) : State
        data class Waiting(val reason: String, val retryInSec: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.NotSetUp)
    val state: StateFlow<State> = _state

    @Volatile private var out: DataOutputStream? = null

    internal fun setState(s: State) { _state.value = s }

    internal fun attach(o: DataOutputStream?) { out = o }

    /** Sends a frame if the watch is connected; returns false otherwise. */
    fun send(frame: Frame): Boolean {
        val o = out ?: return false
        return try {
            synchronized(o) { frame.write(o) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Watch address lives in app-private prefs, never in source control.
    private const val PREFS = "pace"
    private const val KEY_ADDRESS = "watch_address"

    fun address(c: Context): String? =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ADDRESS, null)

    fun setAddress(c: Context, address: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, address.uppercase()).apply()
    }
}
