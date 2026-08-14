package com.thrive.app.update

import com.thrive.app.data.remote.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process channel between the background GitHub update check and the UI.
 *
 * The [UpdateCheckWorker] runs in this process, so it can hand an available
 * update straight to whatever screen is open; ThriveRoot collects this and
 * shows the update dialog. When the app is not running, the worker falls back
 * to the system notification instead — nothing depends on a sync server, API
 * key, or IP address. The check is purely against GitHub releases.
 */
object UpdateBus {

    private val _updates = MutableStateFlow<UpdateInfo?>(null)

    /** Latest update found by a check, or null when none is pending. */
    val updates: StateFlow<UpdateInfo?> = _updates.asStateFlow()

    /** Publish an update so the UI can show the dialog. */
    fun publish(update: UpdateInfo) {
        _updates.value = update
    }

    /** Clear the pending update (user dismissed it or started the install). */
    fun clear() {
        _updates.value = null
    }
}
