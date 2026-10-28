package com.dex.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DexApplication : Application() {

    companion object {
        private const val TAG = "DexApplication"
        const val DATA_STORE_NAME = "dex_preferences"

        // DataStore keys
        val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        val KEY_SHOW_CONFIRMATION = booleanPreferencesKey("show_confirmation")
        val KEY_PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_LAST_CONNECTION_TYPE = stringPreferencesKey("last_connection_type")
    }

    val dataStore by preferencesDataStore(DATA_STORE_NAME)

    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DexApplication created")
    }
}