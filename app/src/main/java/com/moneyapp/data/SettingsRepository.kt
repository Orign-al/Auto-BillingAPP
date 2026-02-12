package com.moneyapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val Host = stringPreferencesKey("host")
        val Token = stringPreferencesKey("token")
        val DefaultAccountId = stringPreferencesKey("default_account_id")
        val DefaultCategoryId = stringPreferencesKey("default_category_id")
    }

    val settingsFlow: Flow<SettingsState> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            SettingsState(
                host = preferences[Keys.Host].orEmpty(),
                token = preferences[Keys.Token].orEmpty(),
                defaultAccountId = preferences[Keys.DefaultAccountId].orEmpty(),
                defaultCategoryId = preferences[Keys.DefaultCategoryId].orEmpty()
            )
        }

    suspend fun save(settings: SettingsState) {
        context.dataStore.edit { prefs: Preferences ->
            prefs[Keys.Host] = settings.host.trim()
            prefs[Keys.Token] = settings.token.trim()
            prefs[Keys.DefaultAccountId] = settings.defaultAccountId.trim()
            prefs[Keys.DefaultCategoryId] = settings.defaultCategoryId.trim()
        }
    }
}

data class SettingsState(
    val host: String,
    val token: String,
    val defaultAccountId: String,
    val defaultCategoryId: String
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank()
}
