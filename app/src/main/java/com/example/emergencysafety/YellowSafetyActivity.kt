package com.example.emergencysafety

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class YellowSafetyActivity : AppCompatActivity() {

    private val prefsName = "EmergencyContactSettings"

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (fineGranted || coarseGranted) {
                getLocationAndShare()
            } else {
                Toast.makeText(
                    this,
                    "Konum paylaşmak için konum iznine izin vermelisin.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yellow_safety)

        findViewById<ImageView>(R.id.btnYellowHelp)
            .setOnClickListener {
                sendHelpToFirstContact()
            }

        findViewById<ImageView>(R.id.btnYellowLocation)
            .setOnClickListener {
                shareLocation()
            }

        findViewById<ImageView>(R.id.btnYellowSafetySupport)
            .setOnClickListener {
                sendSafetySupportMessage()
            }
    }

    private fun sendHelpToFirstContact() {
        val prefs = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        )

        val phone = prefs.getString(
            "contact1_phone",
            ""
        )?.trim().orEmpty()

        if (phone.isBlank()) {
            showMissingContact()
            return
        }

        val message = prefs.getString(
            "emergency_message",
            ""
        )?.trim().orEmpty().ifBlank {
            "Acil yardıma ihtiyacım var. Lütfen benimle iletişime geç."
        }

        openWhatsAppMessage(phone, message)
    }

    private fun shareLocation() {
        val phone = getFirstContactPhone() ?: return

        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        getLocationAndShare()
    }

    private fun getLocationAndShare() {
        if (!hasLocationPermission()) {
            return
        }

        val locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        val provider = try {
            when {
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    locationManager.isProviderEnabled(
                        LocationManager.GPS_PROVIDER
                    ) -> LocationManager.GPS_PROVIDER

                locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                ) -> LocationManager.NETWORK_PROVIDER

                locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                ) -> LocationManager.GPS_PROVIDER

                else -> null
            }
        } catch (_: Exception) {
            null
        }

        if (provider == null) {
            Toast.makeText(
                this,
                "Telefonun konum hizmetini açıp tekrar dene.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Konum alınıyor, lütfen bekle...",
            Toast.LENGTH_SHORT
        ).show()

        val handler = Handler(Looper.getMainLooper())
        var completed = false
        lateinit var listener: LocationListener

        val timeoutRunnable = Runnable {
            if (!completed) {
                completed = true

                try {
                    locationManager.removeUpdates(listener)
                } catch (_: Exception) {
                }

                Toast.makeText(
                    this,
                    "Konum alınamadı. GPS ve konum hizmetlerini kontrol edip tekrar dene.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed) return

                completed = true
                handler.removeCallbacks(timeoutRunnable)

                try {
                    locationManager.removeUpdates(this)
                } catch (_: Exception) {
                }

                val phone = getFirstContactPhone() ?: return

                val mapLink =
                    "https://maps.google.com/?q=${location.latitude},${location.longitude}"

                val message =
                    "Acil durumda konumumu paylaşıyorum.\n\n" +
                    "Haritadaki konumum:\n$mapLink"

                openWhatsAppMessage(phone, message)
            }
        }

        try {
            locationManager.requestSingleUpdate(
                provider,
                listener,
                Looper.getMainLooper()
            )

            handler.postDelayed(timeoutRunnable, 20000L)

        } catch (_: SecurityException) {
            Toast.makeText(
                this,
                "Konum izni verilmedi. Ayarlardan izinleri kontrol et.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Konum alınırken bir hata oluştu.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendSafetySupportMessage() {
        val phone = getFirstContactPhone() ?: return

        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        val locationManager =
            getSystemService(LOCATION_SERVICE) as LocationManager

        val provider = try {
            when {
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    locationManager.isProviderEnabled(
                        LocationManager.GPS_PROVIDER
                    ) -> LocationManager.GPS_PROVIDER

                locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                ) -> LocationManager.NETWORK_PROVIDER

                locationManager.isProviderEnabled(
                    LocationManager.GPS_PROVIDER
                ) -> LocationManager.GPS_PROVIDER

                else -> null
            }
        } catch (_: Exception) {
            null
        }

        if (provider == null) {
            Toast.makeText(
                this,
                "Telefonun konum hizmetini açıp tekrar dene.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Güvenlik için güncel konum alınıyor...",
            Toast.LENGTH_SHORT
        ).show()

        val handler = Handler(Looper.getMainLooper())
        var completed = false
        lateinit var listener: LocationListener

        val timeoutRunnable = Runnable {
            if (!completed) {
                completed = true

                try {
                    locationManager.removeUpdates(listener)
                } catch (_: Exception) {
                }

                AlertDialog.Builder(this)
                    .setTitle("Konum alınamadı")
                    .setMessage(
                        "Güvenlik mesajına yanlış konum eklememek için " +
                        "mesaj açılmadı. Açık alanda veya pencere kenarında " +
                        "tekrar deneyebilirsin."
                    )
                    .setPositiveButton("TAMAM", null)
                    .show()
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed) return

                completed = true
                handler.removeCallbacks(timeoutRunnable)

                try {
                    locationManager.removeUpdates(this)
                } catch (_: Exception) {
                }

                val currentPhone = getFirstContactPhone() ?: return

                val mapLink =
                    "https://maps.google.com/?q=${location.latitude},${location.longitude}"

                val message =
                    "Kendimi güvende hissetmiyorum. " +
                    "Lütfen konumuma gel ve benimle iletişime geç.\n\n" +
                    "📍 Konumum:\n$mapLink"

                openWhatsAppMessage(currentPhone, message)
            }
        }

        try {
            locationManager.requestSingleUpdate(
                provider,
                listener,
                Looper.getMainLooper()
            )

            handler.postDelayed(timeoutRunnable, 25000L)

        } catch (_: SecurityException) {
            Toast.makeText(
                this,
                "Konum izni verilmedi. Telefon ayarlarını kontrol et.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Konum alınırken bir hata oluştu.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun getFirstContactPhone(): String? {
        val phone = getSharedPreferences(
            prefsName,
            MODE_PRIVATE
        ).getString("contact1_phone", "")?.trim().orEmpty()

        if (phone.isBlank()) {
            showMissingContact()
            return null
        }

        return phone
    }

    private fun hasLocationPermission(): Boolean {
        return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun openWhatsAppMessage(
        rawPhone: String,
        message: String
    ) {
        val phone = normalizePhoneNumber(rawPhone)

        if (phone.isBlank()) {
            Toast.makeText(
                this,
                "Birinci kişinin telefon numarası geçerli görünmüyor.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val whatsappUri = Uri.parse(
            "https://wa.me/$phone?text=${Uri.encode(message)}"
        )

        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, whatsappUri)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "WhatsApp açılamadı. Kurulu olduğunu kontrol et.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "WhatsApp açılamadı. Lütfen tekrar dene.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showMissingContact() {
        AlertDialog.Builder(this)
            .setTitle("Acil kişi kayıtlı değil")
            .setMessage(
                "Lütfen kırmızı menüdeki Rehber ve Mesaj Ayarları " +
                "bölümünden birinci kişinin telefon numarasını kaydet."
            )
            .setPositiveButton("TAMAM", null)
            .show()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun normalizePhoneNumber(rawPhone: String): String {
        var phone = rawPhone.filter {
            it.isDigit() || it == '+'
        }

        if (phone.startsWith("00")) {
            phone = phone.removePrefix("00")
        }

        if (phone.startsWith("+")) {
            phone = phone.drop(1).filter { it.isDigit() }
        } else {
            phone = phone.filter { it.isDigit() }

            when {
                phone.length == 11 && phone.startsWith("0") -> {
                    phone = "90" + phone.drop(1)
                }

                phone.length == 10 && phone.startsWith("5") -> {
                    phone = "90$phone"
                }
            }
        }

        return phone
    }
}
