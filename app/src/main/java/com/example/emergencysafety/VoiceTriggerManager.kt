package com.example.emergencysafety

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import java.util.Locale

class VoiceTriggerManager(private val context: Context) {

    private val tag = "VoiceTriggerManager"
    private val diagnosticFileName = "EmergencySafety-diagnostics.txt"
    private val diagnosticLock = Any()

    private var diagnosticUri: Uri? = null
    private var diagnosticFile: File? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null

    private var onRedStartCallback: (() -> Unit)? = null
    private var onRedStopCallback: (() -> Unit)? = null
    private var onSirenStartCallback: (() -> Unit)? = null
    private var onSirenStopCallback: (() -> Unit)? = null

    private var isListening = false
    private val prefsName = "VoiceCommandSettings"

    private var pendingCommand: String? = null
    private var pendingCommandTime = 0L
    private val confirmationWindowMs = 3000L

    private val handler = Handler(Looper.getMainLooper())
    private var restartPending = false
    private var restartCount = 0

    // TEŞHİS TESTİ KAPATILDI:
    // KOD=7 sonrasında otomatik yeniden deneme yapılabilir.
    private var pauseAfterNoMatch = false

    private fun diagnostic(event: String) {
        val line = "${System.currentTimeMillis()} | " +
            "${SystemClock.elapsedRealtime()} | $event\n"

        Log.d(tag, line.trim())

        synchronized(diagnosticLock) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    var uri = diagnosticUri

                    if (uri == null) {
                        val collection =
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI

                        val projection = arrayOf(
                            MediaStore.MediaColumns._ID
                        )

                        val selection =
                            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                                "${MediaStore.MediaColumns.RELATIVE_PATH}=?"

                        val selectionArgs = arrayOf(
                            diagnosticFileName,
                            Environment.DIRECTORY_DOWNLOADS + "/"
                        )

                        context.contentResolver.query(
                            collection,
                            projection,
                            selection,
                            selectionArgs,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                uri = Uri.withAppendedPath(
                                    collection,
                                    id.toString()
                                )
                            }
                        }

                        if (uri == null) {
                            val values = ContentValues().apply {
                                put(
                                    MediaStore.MediaColumns.DISPLAY_NAME,
                                    diagnosticFileName
                                )
                                put(
                                    MediaStore.MediaColumns.MIME_TYPE,
                                    "text/plain"
                                )
                                put(
                                    MediaStore.MediaColumns.RELATIVE_PATH,
                                    Environment.DIRECTORY_DOWNLOADS + "/"
                                )
                            }

                            uri = context.contentResolver.insert(
                                collection,
                                values
                            )
                        }

                        diagnosticUri = uri
                    }

                    val target = uri
                        ?: throw IllegalStateException(
                            "Teşhis dosyası oluşturulamadı"
                        )

                    context.contentResolver.openOutputStream(
                        target,
                        "wa"
                    )?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(line)
                    } ?: throw IllegalStateException(
                        "Teşhis dosyası açılamadı"
                    )
                } else {
                    val file = diagnosticFile ?: File(
                        context.getExternalFilesDir(null)
                            ?: context.filesDir,
                        diagnosticFileName
                    ).also {
                        diagnosticFile = it
                    }

                    file.appendText(line, Charsets.UTF_8)
                }
            } catch (exception: Exception) {
                Log.e(tag, "TEŞHİS DOSYASINA YAZILAMADI", exception)
            }
        }
    }

    private fun normalize(text: String): String {
        var result = text.lowercase(Locale("tr", "TR"))
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        result = result
            .replace(
                Regex("(?<!\\p{L})kırk\\s*bir(?!\\p{L})"),
                "41"
            )
            .replace(
                Regex("(?<!\\p{L})kırk\\s*iki(?!\\p{L})"),
                "42"
            )

        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun getCommand(
        key: String,
        defaultValue: String
    ): String {
        val prefs = context.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )
        return normalize(prefs.getString(key, defaultValue) ?: defaultValue)
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val vibrator =
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(pattern, -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (exception: Exception) {
            Log.e(tag, "Titreşim verilemedi.", exception)
            diagnostic("TITRESIM_HATASI")
        }
    }

    private data class VoiceCommand(
        val key: String,
        val phrase: String,
        val action: () -> Unit
    )

    private fun getCommands(): List<VoiceCommand> {
        val commands = mutableListOf<VoiceCommand>()

        onRedStartCallback?.let {
            commands.add(
                VoiceCommand(
                    "red_start",
                    getCommand("red_start", "kırmızı 41"),
                    it
                )
            )
        }

        onRedStopCallback?.let {
            commands.add(
                VoiceCommand(
                    "red_stop",
                    getCommand("red_stop", "kırmızı 42"),
                    it
                )
            )
        }

        onSirenStartCallback?.let {
            commands.add(
                VoiceCommand(
                    "siren_start",
                    getCommand("siren_start", "siren aç"),
                    it
                )
            )
        }

        onSirenStopCallback?.let {
            commands.add(
                VoiceCommand(
                    "siren_stop",
                    getCommand("siren_stop", "siren kapat"),
                    it
                )
            )
        }

        return commands.filter { it.phrase.isNotBlank() }
            .sortedByDescending { it.phrase.length }
    }

    private fun handleCommand(
        commandKey: String,
        action: () -> Unit
    ) {
        val now = SystemClock.elapsedRealtime()

        if (pendingCommand == commandKey &&
            now - pendingCommandTime <= confirmationWindowMs
        ) {
            pendingCommand = null
            pendingCommandTime = 0L

            diagnostic("KOMUT_DOGRULANDI: $commandKey")

            try {
                action()
            } catch (exception: Exception) {
                Log.e(tag, "Komut işlemi başarısız oldu.", exception)
                diagnostic("KOMUT_ISLEM_HATASI: $commandKey")
            } finally {
                vibrate(longArrayOf(0, 100, 70, 100))
            }
        } else {
            pendingCommand = commandKey
            pendingCommandTime = now
            diagnostic("ILK_KOMUT_ALGISI: $commandKey")
        }
    }

    private fun processRecognizedText(text: String) {
        val spoken = normalize(text)
        if (spoken.isBlank()) return

        diagnostic("TANIMA_METNI_ALINDI")

        val commands = getCommands()
        if (commands.isEmpty()) return

        val words = spoken.split(" ")
        var index = 0

        while (index < words.size) {
            var matched: VoiceCommand? = null
            var matchedWordCount = 0

            for (command in commands) {
                val commandWords = command.phrase.split(" ")
                val end = index + commandWords.size

                if (end <= words.size &&
                    words.subList(index, end) == commandWords
                ) {
                    matched = command
                    matchedWordCount = commandWords.size
                    break
                }
            }

            if (matched != null) {
                handleCommand(matched.key, matched.action)
                index += matchedWordCount
            } else {
                index++
            }
        }
    }

    fun startListening(
        onRedStart: () -> Unit,
        onRedStop: () -> Unit,
        onSirenStart: () -> Unit,
        onSirenStop: () -> Unit
    ) {
        onRedStartCallback = onRedStart
        onRedStopCallback = onRedStop
        onSirenStartCallback = onSirenStart
        onSirenStopCallback = onSirenStop

        if (isListening && speechRecognizer != null) {
            diagnostic("DINLEME_ZATEN_AKTIF")
            return
        }

        diagnostic("DINLEME_BASLATMA_ISTEGI")
        isListening = true

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            diagnostic("HATA: SES_TANIMA_KULLANILAMIYOR")
            isListening = false
            return
        }

        if (speechRecognizer == null) {
            try {
                speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(createRecognitionListener())
                    }
                diagnostic("SPEECH_RECOGNIZER_OLUSTURULDU")
            } catch (exception: Exception) {
                diagnostic(
                    "RECOGNIZER_OLUSTURMA_HATASI: " +
                        exception.javaClass.simpleName
                )
                isListening = false
                return
            }
        }

        recognizerIntent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    context.packageName
                )
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

        scheduleRestart(150)
    }

    private fun scheduleRestart(delayMs: Long = 250L) {
        if (!isListening) {
            diagnostic("YENIDEN_BASLATMA_ATLANDI: DINLEME_KAPALI")
            return
        }

        if (restartPending) {
            diagnostic("YENIDEN_BASLATMA_ATLANDI: ZATEN_BEKLIYOR")
            return
        }

        restartPending = true
        diagnostic("YENIDEN_BASLATMA_PLANLANDI: ${delayMs}ms")

        handler.postDelayed({
            restartPending = false

            if (!isListening) {
                diagnostic("YENIDEN_BASLATMA_IPTAL: DINLEME_KAPALI")
                return@postDelayed
            }

            try {
                restartCount++
                diagnostic("START_LISTENING_CAGRISI: #$restartCount")

                speechRecognizer?.startListening(recognizerIntent)
                    ?: diagnostic("HATA: RECOGNIZER_NULL")
            } catch (exception: Exception) {
                diagnostic(
                    "START_LISTENING_HATASI: " +
                        exception.javaClass.simpleName
                )
                Log.e(tag, "Dinleme başlatılamadı.", exception)
                scheduleRestart(700)
            }
        }, delayMs)
    }

    private fun createRecognitionListener() =
        object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                diagnostic("DINLEME_HAZIR")
            }

            override fun onBeginningOfSpeech() {
                diagnostic("KONUSMA_BASLADI")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                diagnostic("KONUSMA_BITTI")
            }

            override fun onError(error: Int) {
                diagnostic("DINLEME_HATASI: KOD=$error")

                if (error == SpeechRecognizer.ERROR_NO_MATCH &&
                    pauseAfterNoMatch
                ) {
                    diagnostic(
                        "TEST: KOD_7_SONRASI_OTOMATIK_YENIDEN_BASLATMA_DURDU"
                    )
                    return
                }

                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    diagnostic("NO_MATCH: 2000ms SONRA TEKRAR DENE")
                    scheduleRestart(2000)
                } else {
                    scheduleRestart(500)
                }
            }

            override fun onResults(results: Bundle?) {
                diagnostic("SONUC_GELDI")

                try {
                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    if (matches != null && isListening) {
                        for (recognizedText in matches) {
                            processRecognizedText(recognizedText)
                            if (recognizedText.isNotBlank()) break
                        }
                    } else {
                        diagnostic("SONUC_BOS_VEYA_DINLEME_KAPALI")
                    }
                } catch (exception: Exception) {
                    diagnostic(
                        "SONUC_ISLEME_HATASI: " +
                            exception.javaClass.simpleName
                    )
                    Log.e(tag, "Sonuç işlenemedi.", exception)
                } finally {
                    scheduleRestart(250)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                diagnostic("KISMİ_SONUC_GELDI")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                diagnostic("TANIMA_EVENTI: $eventType")
            }
        }

    fun stopListening() {
        diagnostic("DINLEME_DURDURMA_ISTEGI")
        isListening = false
        pendingCommand = null
        pendingCommandTime = 0L

        handler.removeCallbacksAndMessages(null)
        restartPending = false

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            diagnostic("SPEECH_RECOGNIZER_YOK_EDILDI")
        } catch (exception: Exception) {
            Log.e(
                tag,
                "Ses tanıma kapatılırken hata oluştu.",
                exception
            )
            diagnostic(
                "DURDURMA_HATASI: ${exception.javaClass.simpleName}"
            )
        }

        speechRecognizer = null
        recognizerIntent = null
    }
}
