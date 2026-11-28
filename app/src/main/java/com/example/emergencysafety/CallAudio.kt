package com.example.emergencysafety

import android.app.AlertDialog
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

@Suppress("DEPRECATION")
internal class CallAudio(private val activity: AppCompatActivity) {
    private val audio by lazy { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var previousMode: Int? = null
    private var previousSpeaker = false
    private var previousSco = false
    private var previousDevice: AudioDeviceInfo? = null
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && previousMode != null) route(2)
    }
    fun start() {
        if (previousMode != null) return
        previousMode = audio.mode
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) previousDevice = audio.communicationDevice
            else {
                previousSpeaker = audio.isSpeakerphoneOn
                previousSco = audio.isBluetoothScoOn
            }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            route(0)
        }.onFailure { Toast.makeText(activity, "Sistem ses çıkışı kullanılıyor.", Toast.LENGTH_SHORT).show() }
    }
    fun choose() {
        AlertDialog.Builder(activity, R.style.YellowPhotoDialog).setTitle("Ses çıkışı")
            .setItems(arrayOf("Ahize", "Hoparlör (handsfree)", "Bluetooth / araç")) { _, i -> route(i) }.show()
    }
    private fun route(i: Int) {
        if (previousMode == null) return
        if (i == 2 && Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(activity,
                android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val device = audio.availableCommunicationDevices.firstOrNull {
                    when (i) {
                        0 -> it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        1 -> it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        else -> it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
                    }
                }
                device != null && audio.setCommunicationDevice(device)
            } else {
                if (i == 2) {
                    if (!audio.isBluetoothScoAvailableOffCall || audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return@runCatching false
                    audio.isSpeakerphoneOn = false
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                } else {
                    audio.stopBluetoothSco()
                    audio.isBluetoothScoOn = false
                    audio.isSpeakerphoneOn = i == 1
                }
                true
            }
        }.getOrDefault(false)
        if (!ok) Toast.makeText(activity, "Seçilen ses çıkışı kullanılamıyor.", Toast.LENGTH_SHORT).show()
    }
    fun close() {
        val mode = previousMode ?: return
        previousMode = null
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
                previousDevice?.let { audio.setCommunicationDevice(it) }
            } else {
                if (!previousSco) audio.stopBluetoothSco()
                audio.isBluetoothScoOn = previousSco
                audio.isSpeakerphoneOn = previousSpeaker
            }
        } catch (_: SecurityException) { /* Route permission can change during a call. */ }
        finally { runCatching { audio.mode = mode } }
    }
}