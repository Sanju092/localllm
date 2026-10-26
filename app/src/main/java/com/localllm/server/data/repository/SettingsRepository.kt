package com.localllm.server.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localllm.server.data.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val PORT = intPreferencesKey("port")
        val BIND = stringPreferencesKey("bind_address")
        val CTX = intPreferencesKey("context_size")
        val THREADS = intPreferencesKey("threads")
        val TEMP = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val API_KEY = stringPreferencesKey("api_key")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val KEEP_SCREEN = booleanPreferencesKey("keep_screen_on")
        val BOOT = booleanPreferencesKey("start_on_boot")
        val MODEL = stringPreferencesKey("selected_model")
    }

    val config: Flow<ServerConfig> = context.dataStore.data.map { p ->
        val d = ServerConfig()
        ServerConfig(
            port = p[Keys.PORT] ?: d.port,
            bindAddress = p[Keys.BIND] ?: d.bindAddress,
            contextSize = p[Keys.CTX] ?: d.contextSize,
            threads = p[Keys.THREADS] ?: d.threads,
            temperature = p[Keys.TEMP] ?: d.temperature,
            maxTokens = p[Keys.MAX_TOKENS] ?: d.maxTokens,
            apiKey = p[Keys.API_KEY] ?: d.apiKey,
            autoStartServer = p[Keys.AUTO_START] ?: d.autoStartServer,
            keepScreenOn = p[Keys.KEEP_SCREEN] ?: d.keepScreenOn,
            startOnBoot = p[Keys.BOOT] ?: d.startOnBoot,
            selectedModelFileName = p[Keys.MODEL],
        )
    }

    suspend fun getConfig(): ServerConfig = config.first()

    suspend fun update(transform: (ServerConfig) -> ServerConfig) {
        context.dataStore.edit { p ->
            val current = ServerConfig(
                port = p[Keys.PORT] ?: 8080,
                bindAddress = p[Keys.BIND] ?: "0.0.0.0",
                contextSize = p[Keys.CTX] ?: 4096,
                threads = p[Keys.THREADS] ?: 0,
                temperature = p[Keys.TEMP] ?: 0.7f,
                maxTokens = p[Keys.MAX_TOKENS] ?: 1024,
                apiKey = p[Keys.API_KEY] ?: "",
                autoStartServer = p[Keys.AUTO_START] ?: true,
                keepScreenOn = p[Keys.KEEP_SCREEN] ?: false,
                startOnBoot = p[Keys.BOOT] ?: false,
                selectedModelFileName = p[Keys.MODEL],
            )
            val n = transform(current)
            p[Keys.PORT] = n.port
            p[Keys.BIND] = n.bindAddress
            p[Keys.CTX] = n.contextSize
            p[Keys.THREADS] = n.threads
            p[Keys.TEMP] = n.temperature
            p[Keys.MAX_TOKENS] = n.maxTokens
            p[Keys.API_KEY] = n.apiKey
            p[Keys.AUTO_START] = n.autoStartServer
            p[Keys.KEEP_SCREEN] = n.keepScreenOn
            p[Keys.BOOT] = n.startOnBoot
            if (n.selectedModelFileName != null) p[Keys.MODEL] = n.selectedModelFileName else p.remove(Keys.MODEL)
        }
    }
}
