
package com.example.emergencysafety

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

class PermissionGateActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_OVERLAY = 201

        const val EXTRA_REQUEST_INITIAL_PERMISSIONS =
            "com.example.emergencysafety.REQUEST_INITIAL_PERMISSIONS"
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
            openAccountScreen()
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
                openAccountScreen()
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

    private fun openAccountScreen() {
        val intent = Intent(
            this,
            AuthActivity::class.java
        ).apply {
            putExtra(
                EXTRA_REQUEST_INITIAL_PERMISSIONS,
                true
            )
        }

        startActivity(intent)
        finish()
    }
}
