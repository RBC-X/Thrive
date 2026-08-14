package com.thrive.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Clipboard helpers with an explicit result, so the UI can show a real
 * confirmation and fall back when the device restricts clipboard access
 * (some OEMs block [ClipboardManager.setPrimaryClip] or apps in the
 * background on Android 10+).
 */
object Clipboard {

    /**
     * Attempts to put [text] on the system clipboard.
     *
     * @return true when the copy succeeded and the clipboard now holds [text].
     */
    fun copy(context: Context, label: String, text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            clip.setPrimaryClip(ClipData.newPlainText(label, text))
            // setPrimaryClip is synchronous on every supported API; reading it
            // back verifies the device actually stored the value.
            clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == text
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Reads the current clipboard text, or null when unavailable/empty. */
    fun read(context: Context): String? = try {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
