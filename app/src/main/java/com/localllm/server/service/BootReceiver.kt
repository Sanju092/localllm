package com.localllm.server.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.localllm.server.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (settings.getConfig().startOnBoot) LlmServerService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
