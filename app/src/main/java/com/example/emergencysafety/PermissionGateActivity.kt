
package com.example.emergencysafety

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionGateActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY = 201
        private const val REQUEST_RUNTIME = 202
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkOverlayPermissionFirst()
    }

    private fun checkOverlayPermissionFirst() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            startActivityForResult(intent, REQUEST_OVERLAY)
        } else {
            requestOtherPermissions()
        }
    }

    @Deprecated("Deprecated by Android, retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_OVERLAY) {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
            ) {
                requestOtherPermissions()
            } else {
                showOverlayPermissionDialog()
            }
        }
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("İzin gerekli")
            .setMessage(
                "EmergencySafety uygulamasının bazı güvenlik " +
                "özellikleri için diğer uygulamaların üzerinde " +
                "görünme iznine ihtiyacı var."
            )
            .setCancelable(false)
            .setPositiveButton("TEKRAR DENE") { _, _ ->
                checkOverlayPermissionFirst()
            }
            .setNegativeButton("UYGULAMADAN ÇIK") { _, _ ->
                finish()
            }
            .show()
    }

    private fun requestOtherPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            openAccountScreen()
            return
        }

        ActivityCompat.requestPermissions(
            this,
            missingPermissions.toTypedArray(),
            REQUEST_RUNTIME
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_RUNTIME) {
            openAccountScreen()
        }
    }

    private fun openAccountScreen() {
        startActivity(
            Intent(this, AuthActivity::class.java)
        )
        finish()
    }
}
