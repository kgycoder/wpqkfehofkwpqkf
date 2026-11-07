package com.arena.agent

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arena.agent.agent.AgentViewModel
import com.arena.agent.device.AppVisibility
import com.arena.agent.ui.AgentScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: AgentViewModel = viewModel()
            AgentScreen(model)
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.activity = this
    }

    override fun onPause() {
        runCatching { CookieManager.getInstance().flush() }
        super.onPause()
    }

    override fun onStop() {
        if (AppVisibility.activity === this) AppVisibility.activity = null
        super.onStop()
    }
}
