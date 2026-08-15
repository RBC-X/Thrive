package com.thrive.app.data.local

import android.content.SharedPreferences

/** Lightweight persistence for user settings and state. */
class SettingsStore(private val prefs: SharedPreferences) {

    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getString(key: String, def: String? = null) = prefs.getString(key, def)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    fun getFloat(key: String, def: Float) = prefs.getFloat(key, def)
    fun remove(key: String) = prefs.edit().remove(key).apply()
}
