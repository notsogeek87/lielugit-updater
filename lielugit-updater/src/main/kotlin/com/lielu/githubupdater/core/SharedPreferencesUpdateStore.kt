package com.lielu.githubupdater.core

import android.content.Context

internal class SharedPreferencesUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("lielugit_updater", Context.MODE_PRIVATE)

    override var cacheKey: String?
        get() = prefs.getString("key", null)
        set(value) = prefs.edit().putString("key", value).apply()

    override var lastCheckMillis: Long
        get() = prefs.getLong("last_check", 0)
        set(value) = prefs.edit().putLong("last_check", value).apply()

    override var cachedUpdateJson: String?
        get() = prefs.getString("update", null)
        set(value) = prefs.edit().putString("update", value).apply()
}
