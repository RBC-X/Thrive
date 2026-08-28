package com.thrive.app.data.local

import android.content.SharedPreferences
import androidx.core.content.edit

/** Lightweight persistence for user settings and state. */
class SettingsStore(private val prefs: SharedPreferences) {

    fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putLong(key: String, value: Long) = prefs.edit { putLong(key, value) }
    fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    /** Use for lifecycle-bound state that must reach disk before the process can stop. */
    fun putStringImmediate(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()
    fun getString(key: String, def: String? = null) = prefs.getString(key, def)
    fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }
    fun getFloat(key: String, def: Float) = prefs.getFloat(key, def)
    fun remove(key: String) = prefs.edit { remove(key) }

    /** Persisted set of appliances the user owns (lowercase). */
    fun getAppliances(): Set<String> {
        val raw = getString("appliances", null) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun setAppliances(appliances: Set<String>) {
        putString("appliances", appliances.joinToString(","))
    }
}
