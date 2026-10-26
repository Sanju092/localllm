package com.localllm.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.localllm.server.data.repository.ModelRepository
import com.localllm.server.data.repository.SettingsRepository
import com.localllm.server.server.ServerStateHolder
import com.localllm.server.server.ServerStatus
import com.localllm.server.service.LlmServerService
import com.localllm.server.ui.AppNavigation
import com.localllm.server.ui.theme.LocalLLMServerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var serverState: ServerStateHolder

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            val config = settingsRepository.getConfig()
            if (savedInstanceState == null && config.autoStartServer &&
                serverState.state.value.status == ServerStatus.STOPPED &&
                modelRepository.installed.value.isNotEmpty()
            ) {
                LlmServerService.start(this@MainActivity)
            }
        }
        lifecycleScope.launch {
            settingsRepository.config.collect { cfg ->
                if (cfg.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent {
            LocalLLMServerTheme {
                AppNavigation()
            }
        }
    }
}
