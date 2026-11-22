package ai.kira.mobile

import android.content.Intent
import android.os.Bundle
import ai.kira.mobile.assistant.AiAssistantOverlayService
import ai.kira.mobile.application.KiraContainer
import ai.kira.mobile.ui.theme.KiraPalette
import ai.kira.mobile.ui.theme.KiraTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

/** Thin Android entry point: lifecycle, inbound intents and asynchronous app startup only. */
class MainActivity : ComponentActivity() {
    private var overlayRequest by mutableStateOf<OverlayLaunchRequest?>(null)
    private var overlayRunning by mutableStateOf(false)
    private var appContainer by mutableStateOf<KiraContainer?>(null)
    private var startupError by mutableStateOf<String?>(null)
    private var startupInProgress = false
    private val startupExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayRunning = AiAssistantOverlayService.isRunning
        submitOverlayIntent(intent)
        setContent {
            KiraTheme {
                val container = appContainer
                if (container == null) {
                    StartupScreen(startupError, ::loadContainer)
                } else {
                    KiraApp(container, overlayRequest, overlayRunning)
                }
            }
        }
        loadContainer()
    }

    override fun onResume() {
        super.onResume()
        overlayRunning = AiAssistantOverlayService.isRunning
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        submitOverlayIntent(intent)
    }

    override fun onDestroy() {
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadContainer() {
        if (startupInProgress || appContainer != null) return
        startupInProgress = true
        startupError = null
        startupExecutor.execute {
            try {
                // KiraContainer opens SQLite and initializes the local library;
                // keep that work off the Activity/UI thread.
                val container = (application as KiraMobileApplication).container
                runOnUiThread {
                    appContainer = container
                    startupInProgress = false
                }
            } catch (error: Exception) {
                runOnUiThread {
                    startupError = error.message ?: "آماده‌سازی هسته محلی Kira ناموفق بود."
                    startupInProgress = false
                }
            }
        }
    }

    private fun submitOverlayIntent(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_OPEN_ROUTE) ?: return
        overlayRequest = OverlayLaunchRequest(
            route,
            intent.getStringExtra(EXTRA_CHAT_TEXT).orEmpty(),
            (overlayRequest?.sequence ?: 0L) + 1L
        )
    }

    companion object {
        const val EXTRA_OPEN_ROUTE = "ai.kira.mobile.extra.OPEN_ROUTE"
        const val EXTRA_CHAT_TEXT = "ai.kira.mobile.extra.CHAT_TEXT"
    }
}

@androidx.compose.runtime.Composable
private fun StartupScreen(error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(KiraPalette.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (error == null) {
            CircularProgressIndicator(color = KiraPalette.primary)
            Text("در حال آماده‌سازی هسته محلی Kira…", color = KiraPalette.onBackground)
        } else {
            Text("راه‌اندازی Kira ناموفق بود.", color = KiraPalette.onBackground)
            Text(error, color = KiraPalette.muted)
            Button(onClick = onRetry) { Text("تلاش دوباره") }
        }
    }
}
