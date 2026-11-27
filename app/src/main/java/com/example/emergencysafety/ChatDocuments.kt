package com.example.emergencysafety

import android.app.AlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import de.connect2x.trixnity.core.model.events.m.room.EncryptedFile
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

internal data class ChatDocument(val name: String, val bytes: ByteArray) {
    companion object {
        fun safeName(value: String) = value.filter { it.isLetterOrDigit() || it in " ._()-" }
            .trim().trim('.').take(120).ifBlank { "dosya" }
    }
}

internal class ChatDocuments(private val activity: AppCompatActivity, private val scope: () -> CoroutineScope,
    private val current: () -> Pair<String, String>?, private val send: (ChatDocument, String) -> Unit) {
    private var target: Pair<String, String>? = null
    private var saving: Pair<Pair<String, String>, EncryptedFile>? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val dest = target
        target = null
        if (uri != null && dest != null && dest == current()) confirm(uri, dest)
    }
    private val saver = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = saving
        saving = null
        if (uri != null && request != null && current() == request.first) scope().launch {
            try {
                val bytes = MatrixChatManager.readAttachment(activity.applicationContext, request.second)
                if (current() != request.first) return@launch
                withContext(Dispatchers.IO) {
                    requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
                notice("Dosya kaydedildi.")
            } catch (e: TimeoutCancellationException) { notice("Dosya indirme zaman aşımına uğradı.") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Dosya kaydedilemedi.") }
        }
    }

    fun select() {
        target = current() ?: return
        picker.launch(arrayOf("*/*"))
    }
    fun save(name: String, file: EncryptedFile) {
        saving = (current() ?: return) to file
        saver.launch(ChatDocument.safeName(name))
    }
    private fun confirm(uri: Uri, dest: Pair<String, String>) = scope().launch {
        try {
            val resolver = activity.contentResolver
            val name = withContext(Dispatchers.IO) {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "dosya"
            }
            if (current() != dest) return@launch
            AlertDialog.Builder(activity, R.style.YellowPhotoDialog).setTitle("Dosya gönderilsin mi?")
                .setMessage(ChatDocument.safeName(name)).setNegativeButton("Vazgeç", null)
                .setPositiveButton("Gönder") { _, _ -> read(uri, name, dest) }.show()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice("Dosya seçilemedi.") }
    }
    private fun read(uri: Uri, name: String, dest: Pair<String, String>) = scope().launch {
        try {
            val bytes = withContext(Dispatchers.IO) {
                val out = ByteArrayOutputStream()
                requireNotNull(activity.contentResolver.openInputStream(uri)).use { stream ->
                    val buffer = ByteArray(16384)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 8 * 1024 * 1024) throw TooLarge()
                        out.write(buffer, 0, n)
                    }
                }
                out.toByteArray()
            }
            if (current() == dest) send(ChatDocument(ChatDocument.safeName(name), bytes), dest.second)
        } catch (_: TooLarge) { notice("En fazla 8 MiB büyüklüğünde dosya gönderebilirsin.") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice("Dosya okunamadı.") }
    }
    private class TooLarge : Exception()
    private fun notice(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
