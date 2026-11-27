package it.tvguida

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Riconoscimento vocale (microfono) e lettura delle risposte (sintesi vocale) in italiano. */
class Voice(private val ctx: Context) {
    var onText: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}
    var onFail: (String) -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) { tts?.language = Locale.ITALY; ttsReady = true }
        }
    }

    fun listen() {
        stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onFail("Riconoscimento vocale non disponibile su questo telefono"); return }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                onListening(false)
                onFail(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Non ho sentito nulla"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Serve il permesso del microfono"
                    else -> "Errore del riconoscimento vocale ($error)"
                })
            }
            override fun onResults(results: Bundle?) {
                onListening(false)
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) onText(t)
            }
        })
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        onListening(true)
        r.startListening(intent)
    }

    fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "risposta")
    }

    fun stopSpeaking() { tts?.stop() }

    fun release() {
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
    }
}
