package com.linguaflow.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.engine.SttState
import com.linguaflow.app.engine.TtsPlaybackState
import com.linguaflow.app.engine.stt.SherpaWhisperSpeechToTextEngine
import com.linguaflow.app.engine.stt.SmartSpeechToTextEngine
import com.linguaflow.app.engine.stt.VoskSpeechToTextEngine
import com.linguaflow.app.engine.tts.AndroidNativeTtsEngine
import com.linguaflow.app.engine.tts.SherpaPiperTextToSpeechEngine
import com.linguaflow.app.engine.tts.SmartTextToSpeechEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SherpaEnginesTest {

    @Test
    fun testModelCatalogContainsFaseB() {
        val whisper = ModelCatalog.STT_WHISPER_EN
        val piper = ModelCatalog.TTS_PIPER_EN

        assertNotNull(whisper)
        assertEquals("stt_whisper_en", whisper.id)
        assertFalse(whisper.isRequired)

        assertNotNull(piper)
        assertEquals("tts_piper_en", piper.id)
        assertFalse(piper.isRequired)
    }

    @Test
    fun testSherpaWhisperEngineGracefulFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ModelDownloadManager(context)
        val vosk = VoskSpeechToTextEngine(context, manager)
        val whisper = SherpaWhisperSpeechToTextEngine(context, manager, vosk)

        assertEquals(SttState.Idle, whisper.state.value)
        assertFalse(whisper.isListening())
    }

    @Test
    fun testSherpaPiperEngineGracefulFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ModelDownloadManager(context)
        val nativeTts = AndroidNativeTtsEngine(context)
        val piper = SherpaPiperTextToSpeechEngine(context, manager, nativeTts)

        assertEquals(TtsPlaybackState.Idle, piper.playbackState.value)
        assertFalse(piper.isSpeaking())

        piper.setSpeechRate(1.2f)
        piper.setPitch(1.0f)
        piper.stop()
    }

    @Test
    fun testSmartEnginesRouting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ModelDownloadManager(context)

        val nativeTts = AndroidNativeTtsEngine(context)
        val piper = SherpaPiperTextToSpeechEngine(context, manager, nativeTts)
        val smartTts = SmartTextToSpeechEngine(piper, nativeTts)

        assertNotNull(smartTts)
        assertEquals(TtsPlaybackState.Idle, smartTts.playbackState.value)

        val vosk = VoskSpeechToTextEngine(context, manager)
        val whisper = SherpaWhisperSpeechToTextEngine(context, manager, vosk)
        val smartStt = SmartSpeechToTextEngine(whisper, vosk)

        assertNotNull(smartStt)
        assertEquals(SttState.Idle, smartStt.state.value)
    }
}
