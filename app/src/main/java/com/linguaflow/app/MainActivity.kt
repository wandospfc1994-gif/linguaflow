package com.linguaflow.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.linguaflow.app.ui.navigation.LinguaflowNavGraph
import com.linguaflow.app.ui.screens.CrashScreen
import com.linguaflow.app.ui.theme.LinguaflowTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val savedCrash: String? = CrashStore.read(this)
        val container = (application as LinguaflowApp).container
        val repository = container.repository
        val modelManager = container.modelManager
        val ttsEngine = container.ttsEngine
        val tutorLlm = container.tutorLlm
        val sttEngine = container.sttEngine

        setContent {
            LinguaflowTheme {
                var crashText by remember { mutableStateOf(savedCrash) }
                val current = crashText

                if (current != null) {
                    CrashScreen(
                        text = current,
                        onCopy = { copyToClipboard(current) },
                        onClear = {
                            CrashStore.clear(this@MainActivity)
                            crashText = null
                        }
                    )
                } else {
                    val navController = rememberNavController()
                    LinguaflowNavGraph(
                        navController = navController,
                        repository = repository,
                        modelManager = modelManager,
                        ttsEngine = ttsEngine,
                        tutorLlm = tutorLlm,
                        sttEngine = sttEngine
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val container = (application as? LinguaflowApp)?.container
        container?.ttsEngine?.stop()
        container?.sttEngine?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        val container = (application as? LinguaflowApp)?.container
        container?.tutorLlm?.unload()
        container?.sttEngine?.release()
        container?.ttsEngine?.release()
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("crash", text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }
}
