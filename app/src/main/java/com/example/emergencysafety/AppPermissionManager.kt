
package com.example.emergencysafety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * EmergencySafety uygulamasının ortak izin yöneticisi.
 *
 * Normal Android izinlerini ve özel ekran iznini
 * tek merkezden kontrol eder.
 */
object AppPermissionManager {

    private const val PREF_NAME = "PermissionSettings"

    /**
     * İlk açılışta istenecek normal izinler.
     *
     * Arka plan konum izni özellikle eklenmemiştir.
     * Gerektiği doğrulanmadan istenmemelidir.
     */
    fun getInitialPermissions(): Array<String> {

        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        return permissions.toTypedArray()
    }

    /**
     * Verilen izinlerden henüz verilmemiş olanları döndürür.
     */
    fun getMissingPermissions(
        context: Context,
        permissions: Array<String>
    ): Array<String> {

        return permissions.filter { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /**
     * Bir iznin verilmiş olup olmadığını kontrol eder.
     */
    fun isGranted(
        context: Context,
        permission: String
    ): Boolean {

        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * İlk açılışta veya sonradan istenen izinlerin
     * kullanıcı tarafından reddedilip reddedilmediğini kaydeder.
     */
    fun recordPermissionResults(
        context: Context,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        val preferences = context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        )

        val editor = preferences.edit()

        permissions.forEachIndexed { index, permission ->

            if (index < grantResults.size) {

                val granted =
                    grantResults[index] ==
                        PackageManager.PERMISSION_GRANTED

                editor.putBoolean(
                    "requested_before_$permission",
                    true
                )

                editor.putBoolean(
                    "denied_before_$permission",
                    !granted
                )
            }
        }

        editor.apply()
    }

    /**
     * İzin daha önce istenmiş mi?
     */
    fun wasRequestedBefore(
        context: Context,
        permission: String
    ): Boolean {

        return context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(
            "requested_before_$permission",
            false
        )
    }

    /**
     * İzin daha önce reddedilmiş mi?
     */
    fun wasDeniedBefore(
        context: Context,
        permission: String
    ): Boolean {

        return context.getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).getBoolean(
            "denied_before_$permission",
            false
        )
    }

    /**
     * Android'in izin açıklaması göstermeye izin verip
     * vermediğini kontrol eder.
     */
    fun shouldShowRationale(
        activity: android.app.Activity,
        permission: String
    ): Boolean {

        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            permission
        )
    }

    /**
     * İzin daha önce reddedilmişse ve Android artık
     * izin penceresini göstermiyorsa ayarlara yönlendirme
     * gerekip gerekmediğini bildirir.
     */
    fun shouldOpenSettings(
        activity: android.app.Activity,
        permission: String
    ): Boolean {

        val deniedBefore = wasDeniedBefore(
            activity,
            permission
        )

        val requestedBefore = wasRequestedBefore(
            activity,
            permission
        )

        val shouldShowRationale = shouldShowRationale(
            activity,
            permission
        )

        return requestedBefore &&
            deniedBefore &&
            !shouldShowRationale
    }

    /**
     * Konum izinlerinden en az birinin bulunup bulunmadığını
     * kontrol eder. Yaklaşık konum da kabul edilir.
     */
    fun hasLocationPermission(
        context: Context
    ): Boolean {

        val fineGranted = isGranted(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val coarseGranted = isGranted(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        return fineGranted || coarseGranted
    }

    /**
     * Özel "diğer uygulamaların üzerinde görünme" izni.
     * Bu izin normal izin penceresiyle istenemez.
     */
    fun hasOverlayPermission(
        context: Context
    ): Boolean {

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(context)
    }
}
