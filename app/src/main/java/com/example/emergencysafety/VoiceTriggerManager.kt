package com.example.emergencysafety

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.util.Locale

class VoiceTriggerManager(private val context: Context) {

    private val tag = "VoiceTriggerManager"
    private val diagnosticFileName = "EmergencySafety-diagnostics.txt"
    private val diagnosticLock = Any()
    private val prefsName = "VoiceCommandSettings"

    private var diagnosticUri: Uri? = null
    private var diagnosticFile: File? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    private var onRedStartCallback: (() -> Unit)? = null
    private var onRedStopCallback: (() -> Unit)? = null
    private var onSirenStartCallback: (() -> Unit)? = null
    private var onSirenStopCallback: (() -> Unit)? = null

    @Volatile
    private var isListening = false

    @Volatile
    private var isPreparingModel = false

    private var pendingCommand: String? = null
    private var pendingCommandTime = 0L
    private val confirmationWindowMs = 3000L

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

    private fun logDetailedError(
        prefix: String,
        exception: Throwable
    ) {
        Log.e(tag, prefix, exception)

        diagnostic("$prefix: ${exception.javaClass.name}")
        diagnostic("HATA_MESAJI: ${exception.message ?: "Mesaj yok"}")

        var cause = exception.cause
        var depth = 1

        while (cause != null && depth <= 5) {
            diagnostic(
                "HATA_NEDENI_$depth: " +
                    "${cause.javaClass.name}: ${cause.message ?: "Mesaj yok"}"
            )
            cause = cause.cause
            depth++
        }

        exception.stackTrace.take(8).forEachIndexed { index, element ->
            diagnostic("STACK_$index: $element")
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

        return normalize(
            prefs.getString(key, defaultValue) ?: defaultValue
        )
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val vibrator =
                context.getSystemService(Context.VIBRATOR_SERVICE)
                    as? Vibrator

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
            logDetailedError("TITRESIM_HATASI", exception)
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

        return commands
            .filter { it.phrase.isNotBlank() }
            .sortedByDescending { it.phrase.length }
    }

    private fun handleCommand(
        commandKey: String,
        action: () -> Unit
    ) {
        if (!isListening) return

        val now = SystemClock.elapsedRealtime()

        if (
            pendingCommand == commandKey &&
            now - pendingCommandTime <= confirmationWindowMs
        ) {
            pendingCommand = null
            pendingCommandTime = 0L

            diagnostic("KOMUT_DOGRULANDI: $commandKey")

            try {
                action()
            } catch (exception: Exception) {
                logDetailedError(
                    "KOMUT_ISLEM_HATASI: $commandKey",
                    exception
                )
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
        if (!isListening) return

        val spoken = normalize(text)
        if (spoken.isBlank()) return

        diagnostic("VOSK_TANIMA_METNI_ALINDI")

        val words = spoken.split(" ")
        val commands = getCommands()
        var index = 0

        while (index < words.size && isListening) {
            var matched: VoiceCommand? = null
            var matchedWordCount = 0

            for (command in commands) {
                val commandWords = command.phrase.split(" ")
                val end = index + commandWords.size

                if (
                    end <= words.size &&
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

    private fun processVoskResult(result: String) {
        if (!isListening || result.isBlank()) return

        try {
            val recognizedText = JSONObject(result).optString("text", "")

            if (recognizedText.isNotBlank()) {
                processRecognizedText(recognizedText)
            }
        } catch (exception: Exception) {
            logDetailedError("VOSK_SONUC_OKUMA_HATASI", exception)
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

        if (isListening || isPreparingModel) {
            diagnostic("VOSK_DINLEME_VEYA_HAZIRLIK_ZATEN_AKTIF")
            return
        }

        diagnostic("VOSK_DINLEME_BASLATMA_ISTEGI")

        isListening = true
        isPreparingModel = true

        try {
            StorageService.unpack(
                context,
                "model",
                "model",
                { loadedModel ->
                    isPreparingModel = false

                    if (!isListening) {
                        try {
                            loadedModel.close()
                        } catch (exception: Exception) {
                            logDetailedError(
                                "KULLANILMAYACAK_MODEL_KAPATMA_HATASI",
                                exception
                            )
                        }

                        diagnostic("VOSK_MODEL_ACILDI_AMA_DINLEME_KAPALI")
                        return@unpack
                    }

                    model = loadedModel
                    diagnostic("VOSK_MODEL_BASARIYLA_ACILDI")

                    startVoskRecognition()
                },
                { exception ->
                    isPreparingModel = false
                    isListening = false

                    logDetailedError("VOSK_MODEL_ACMA_HATASI", exception)
                }
            )
        } catch (exception: Exception) {
            isPreparingModel = false
            isListening = false

            logDetailedError("VOSK_HAZIRLIK_HATASI", exception)
        }
    }

    private fun startVoskRecognition() {
        if (!isListening) return

        try {
            val loadedModel = model
                ?: throw IllegalStateException("Vosk modeli hazır değil")

            val newRecognizer = Recognizer(loadedModel, 16000.0f)
            recognizer = newRecognizer

            val newSpeechService = SpeechService(
                newRecognizer,
                16000.0f
            )

            speechService = newSpeechService

            newSpeechService.startListening(
                object : org.vosk.android.RecognitionListener {

                    override fun onPartialResult(hypothesis: String) {
                        // Ara sonuçlar komut olarak çalıştırılmaz.
                    }

                    override fun onResult(hypothesis: String) {
                        processVoskResult(hypothesis)
                    }

                    override fun onFinalResult(hypothesis: String) {
                        processVoskResult(hypothesis)
                    }

                    override fun onError(exception: Exception) {
                        logDetailedError("VOSK_SES_TANIMA_HATASI", exception)
                    }

                    override fun onTimeout() {
                        diagnostic("VOSK_DINLEME_ZAMAN_ASIMI")
                    }
                }
            )

            diagnostic("VOSK_DINLEME_BASLADI")
        } catch (exception: Exception) {
            logDetailedError("VOSK_DINLEME_BASLATMA_HATASI", exception)

            isListening = false
            releaseVoskResources()
        }
    }

    private fun releaseVoskResources() {
        try {
            speechService?.cancel()
        } catch (exception: Exception) {
            logDetailedError("VOSK_DINLEME_IPTAL_HATASI", exception)
        }

        try {
            speechService?.shutdown()
        } catch (exception: Exception) {
            logDetailedError("VOSK_SES_KAYNAGI_KAPATMA_HATASI", exception)
        }

        speechService = null

        try {
            recognizer?.close()
        } catch (exception: Exception) {
            logDetailedError("VOSK_TANIYICI_KAPATMA_HATASI", exception)
        }

        recognizer = null

        try {
            model?.close()
        } catch (exception: Exception) {
            logDetailedError("VOSK_MODEL_KAPATMA_HATASI", exception)
        }

        model = null
    }

    fun stopListening() {
        diagnostic("VOSK_DINLEME_DURDURMA_ISTEGI")

        isListening = false
        pendingCommand = null
        pendingCommandTime = 0L

        releaseVoskResources()

        isPreparingModel = false

        diagnostic("VOSK_DINLEME_DURDURULDU")
    }
}
