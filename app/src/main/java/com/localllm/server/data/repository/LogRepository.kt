package com.localllm.server.data.repository

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel { INFO, REQUEST, WARN, ERROR }

data class LogEntry(val timestamp: Long, val level: LogLevel, val message: String) {
    val time: String get() = FORMAT.get()!!.format(Date(timestamp))

    private companion object {
        val FORMAT = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    }
}

/** In-memory ring buffer of server events shown in the UI. */
@Singleton
class LogRepository @Inject constructor() {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        Log.println(if (level == LogLevel.ERROR) Log.ERROR else Log.INFO, "LocalLLM", message)
        _logs.update { (it + LogEntry(System.currentTimeMillis(), level, message)).takeLast(MAX) }
    }

    fun clear() = _logs.update { emptyList() }

    private companion object {
        const val MAX = 500
    }
}
