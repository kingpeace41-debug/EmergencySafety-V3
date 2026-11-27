package com.example.emergencysafety

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ChatPhotoPicker(
    private val activity: AppCompatActivity,
    private val scope: () -> CoroutineScope,
    private val currentChat: () -> Pair<String, String>?,
    private val send: (ByteArray, String) -> Unit
) {
    private var target: Pair<String, String>? = null
    private var capture: File? = null

    private fun notifyUser(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private val gallery = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) preview(uri, null)
    }
    private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = capture
        capture = null
        if (saved && file != null) preview(Uri.fromFile(file), file) else file?.delete()
    }
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && target != null && target == currentChat()) launchCamera()
        else if (!granted) notifyUser("Fotoğraf çekmek için kamera izni gerekli.")
    }

    fun open() {
        target = currentChat() ?: return
        AlertDialog.Builder(activity, R.style.YellowPhotoDialog).setTitle("Fotoğraf")
            .setItems(arrayOf("Dosyadan seç", "Anlık fotoğraf çek")) { _, which ->
                if (which == 0) {
                    try { gallery.launch("image/*") }
                    catch (_: android.content.ActivityNotFoundException) { notifyUser("Dosya seçici açılamadı.") }
                } else if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            }.show()
    }

    private fun launchCamera() {
        try {
            val dir = File(activity.cacheDir, "chat-camera").apply { mkdirs() }
            val file = File.createTempFile("photo-", ".jpg", dir)
            capture = file
            camera.launch(FileProvider.getUriForFile(activity, activity.packageName + ".chatphotos", file))
        } catch (_: Exception) {
            capture?.delete()
            capture = null
            notifyUser("Kamera açılamadı.")
        }
    }

    private fun preview(uri: Uri, temp: File?) {
        val destination = target
        if (destination == null || destination != currentChat()) { temp?.delete(); return }
        scope().launch {
            try {
                val (bitmap, jpeg) = withContext(Dispatchers.IO) {
                    val resolver = activity.contentResolver
                    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                            val ratio = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()),
                                maxOf(1, (info.size.height * ratio).toInt()))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    } else {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                        require(opts.outWidth > 0 && opts.outHeight > 0)
                        opts.inSampleSize = 1
                        while (maxOf(opts.outWidth, opts.outHeight) / opts.inSampleSize > 1600) opts.inSampleSize *= 2
                        opts.inJustDecodeBounds = false
                        requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) })
                    }
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    bitmap to output.toByteArray()
                }
                if (currentChat() != destination || activity.isFinishing) return@launch
                val view = ImageView(activity).apply { adjustViewBounds = true; setImageBitmap(bitmap) }
                AlertDialog.Builder(activity, R.style.YellowPhotoDialog).setTitle("Fotoğraf gönderilsin mi?").setView(view)
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Gönder") { _, _ ->
                        if (currentChat() == destination) send(jpeg, destination.second)
                    }.show()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { notifyUser("Fotoğraf okunamadı.") }
            finally { temp?.delete() }
        }
    }
}
