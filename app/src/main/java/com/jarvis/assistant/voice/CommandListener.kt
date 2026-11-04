package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** One-shot transcription of what the user says after the wake word. Must be used on the main thread. */
class CommandListener(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun listen(onResult: (String?) -> Unit) = listen(null, onResult)

    /**
     * [language] is a BCP-47 tag such as "hi-IN". Google's Hindi recognizer also understands Hinglish (English words
     * inside Hindi sentences) and may answer in Devanagari or Roman letters; the command router accepts both.
     */
    fun listen(language: String?, onResult: (String?) -> Unit) {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onResult(null); return }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var delivered = false
        fun deliver(text: String?) {
            if (delivered) return
            delivered = true
            r.destroy()
            if (recognizer === r) recognizer = null
            onResult(text)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) =
                deliver(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.ifEmpty { null })
            override fun onError(error: Int) = deliver(null)
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        if (language != null) {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        }
        r.startListening(intent)
    }

    fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }
}
