
package com.example.emergencysafety

import android.Manifest
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var profilePreview: ImageView? = null

    private val selectProfilePhoto = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && isRegisteredUser()) {
            try {
                val userId = accountPreferences.getString("api_user_id", "").orEmpty()
                if (userId.isBlank()) return@registerForActivityResult
                val destination = File(filesDir, "profile_photo_${userId}.jpg")
                val temporary = File(filesDir, "profile_photo_temp")
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    require(bytes.size <= 5 * 1024 * 1024) { "Fotoğraf 5 MB'dan büyük olamaz." }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("Geçerli bir fotoğraf seçin.")
                    temporary.outputStream().use { output ->
                        require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output))
                    }
                    require(temporary.renameTo(destination)) { "Fotoğraf kaydedilemedi." }
                    profilePreview?.setImageBitmap(BitmapFactory.decodeFile(destination.absolutePath))
                    uploadProfilePhoto(destination)
                } ?: error("Fotoğraf açılamadı.")
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "Fotoğraf seçilemedi.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uploadProfilePhoto(photo: File) {
        val context = applicationContext
        Toast.makeText(this, "Profil fotoğrafı yükleniyor...", Toast.LENGTH_SHORT).show()

        Thread {
            var message = "Profil fotoğrafı sunucuya yüklenemedi."
            try {
                // Oturum suresi dolmussa mevcut oturum yenileme mekanizmasini kullan.
                var token = SecureSessionStore.getAccessToken(context).orEmpty()
                if (token.isBlank()) error("Oturum bulunamadı.")

                fun send(accessToken: String): Pair<Int, String> {
                    val boundary = "ESPhoto" + UUID.randomUUID().toString().replace("-", "")
                    val connection = URL(
                        "https://emergency-safety.com/api/profile-photos/me"
                    ).openConnection() as HttpURLConnection

                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        connection.doOutput = true
                        connection.setRequestProperty("Authorization", "Bearer $accessToken")
                        connection.setRequestProperty(
                            "Content-Type", "multipart/form-data; boundary=$boundary"
                        )
                        connection.outputStream.use { output ->
                            output.write(
                                ("--$boundary\r\n" +
                                    "Content-Disposition: form-data; name=\"photo\"; filename=\"profile.jpg\"\r\n" +
                                    "Content-Type: image/jpeg\r\n\r\n").toByteArray(Charsets.UTF_8)
                            )
                            photo.inputStream().use { it.copyTo(output) }
                            output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                        }
                        val status = connection.responseCode
                        val response = (if (status in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        })?.bufferedReader()?.use { it.readText() }.orEmpty()
                        return status to response
                    } finally {
                        connection.disconnect()
                    }
                }

                var result = send(token)
                if (result.first == 401) {
                    // ConnectionApi, 401 durumunda refresh token ile oturumu yeniler.
                    ConnectionApi.getConnections(context)
                    token = SecureSessionStore.getAccessToken(context).orEmpty()
                    if (token.isNotBlank()) result = send(token)
                }

                if (result.first in 200..299 &&
                    org.json.JSONObject(result.second).optBoolean("success", false)
                ) {
                    message = "Profil fotoğrafı hesabına yüklendi."
                } else {
                    message = "Fotoğraf telefonda kaydedildi, sunucuya yüklenemedi (HTTP ${result.first})."
                }
            } catch (error: Exception) {
                message = "Fotoğraf telefonda kaydedildi; yükleme başarısız: " +
                    (error.localizedMessage ?: "Bağlantı hatası")
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private val volumeDownHandler = Handler(Looper.getMainLooper())
    private var volumeDownRunnable: Runnable? = null

    private val sirenStripHandler = Handler(Looper.getMainLooper())
    private var sirenBannersSwapped = false

    private lateinit var wolfBackground: ImageView
    private lateinit var stripEmergency: ImageView
    private lateinit var stripSafety: ImageView

    private var growlPlayer: MediaPlayer? = null

    private val incomingEmergencyAlert by lazy {
        IncomingEmergencyAlert(this)
    }

    private val instructionPrefs by lazy {
        getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    private val accountPreferences by lazy {
        getSharedPreferences("AccountSettings", Context.MODE_PRIVATE)
    }

    companion object {
        private const val SIREN_STRIP_INTERVAL_MS = 700L
        private const val REQUEST_MICROPHONE_PERMISSION = 401
    }

    private val sirenStripRunnable = object : Runnable {
        override fun run() {
            if (
                !::stripEmergency.isInitialized ||
                !::stripSafety.isInitialized
            ) {
                return
            }

            val isSirenActive = getSharedPreferences(
                "AppSettings",
                Context.MODE_PRIVATE
            ).getBoolean("is_siren_active", false)

            // Yerel alarm kaydını kontrol et; sunucuya istek gönderilmez.
            incomingEmergencyAlert.refresh()

            if (isSirenActive || incomingEmergencyAlert.hasPendingEmergency()) {
                sirenBannersSwapped = !sirenBannersSwapped
                applySirenBannerOrder(sirenBannersSwapped)
            } else {
                sirenBannersSwapped = false
                applySirenBannerOrder(false)
            }

            sirenStripHandler.postDelayed(
                this,
                SIREN_STRIP_INTERVAL_MS
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // KAYITLI KULLANICININ FIREBASE BILDIRIM TOKENINI KAYDET
        FcmTokenRegistrar.registerIfLoggedIn(this)

        wolfBackground = findViewById(R.id.wolfBackground)
        stripEmergency = findViewById(R.id.stripEmergency)
        stripSafety = findViewById(R.id.stripSafety)

        applySirenBannerOrder(false)

        updateWolfImage(
            getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                .getBoolean("is_voice_active", false),
            false
        )

        checkPermissionsAndStartService()

        val btnRedAlertId =
            resources.getIdentifier("btnRedAlert", "id", packageName)

        if (btnRedAlertId != 0) {
            findViewById<View>(btnRedAlertId)?.setOnClickListener {
                vibrateOnClick()
                openRedMenuWithInstruction()
            }
        }

        val btnYellowAlertId =
            resources.getIdentifier("btnYellowAlert", "id", packageName)

        if (btnYellowAlertId != 0) {
            findViewById<View>(btnYellowAlertId)?.setOnClickListener {
                vibrateOnClick()

                if (isRegisteredUser()) {
                    startActivity(
                        Intent(this, YellowSafetyActivity::class.java)
                    )
                } else {
                    showAccountDialog()
                }
            }
        }

        val btnBlueAlertId =
            resources.getIdentifier("btnGreenAlert", "id", packageName)

        if (btnBlueAlertId != 0) {
            findViewById<View>(btnBlueAlertId)?.setOnClickListener {
                vibrateOnClick()
                startActivity(
                    Intent(this, BlueInfoActivity::class.java)
                )
            }
        }

        val btnToggleListeningId =
            resources.getIdentifier("btnToggleListening", "id", packageName)

        if (btnToggleListeningId != 0) {
            findViewById<View>(btnToggleListeningId)?.setOnClickListener {
                vibrateOnClick()
                toggleVoiceService()
            }
        }

        setupAccountButton()

        findViewById<View>(R.id.wolfTouchArea)?.setOnClickListener {
            vibrateOnClick()
            toggleVoiceService()
        }
    }

    // Ana ekrandaki guvenlik paneli: gercek cihaz durumunu gosterir.
    private fun refreshReadinessPanel() {
        val microphoneReady = AppPermissionManager.isGranted(
            this, Manifest.permission.RECORD_AUDIO
        )
        val locationPermission = AppPermissionManager.hasLocationPermission(this)
        val locationManager = getSystemService(Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }
        val locationReady = locationPermission && locationEnabled
        val backgroundReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            AppPermissionManager.isGranted(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val networkReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivity.activeNetwork
            val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
            capabilities?.hasCapability(
                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true
        } else {
            @Suppress("DEPRECATION")
            val active = connectivity.activeNetworkInfo
            active?.isConnected == true
        }
        val ready = microphoneReady && locationReady && backgroundReady && networkReady
        val green = Color.rgb(66, 217, 144)
        val red = Color.rgb(255, 86, 86)
        val gray = Color.rgb(174, 187, 205)

        findViewById<TextView>(R.id.readinessTitle)?.apply {
            text = if (ready) "◆ ACİL DURUMA HAZIR ◆" else "◇ HAZIRLIK KONTROLÜ GEREKLİ ◇"
            setTextColor(if (ready) green else red)
        }
        findViewById<TextView>(R.id.readinessMicrophone)?.apply {
            text = if (microphoneReady) "● Ses" else "○ Ses"
            setTextColor(if (microphoneReady) green else red)
        }
        findViewById<TextView>(R.id.readinessLocation)?.apply {
            text = if (locationReady && backgroundReady) "● Konum" else "○ Konum"
            setTextColor(if (locationReady && backgroundReady) green else red)
        }
        findViewById<TextView>(R.id.readinessConnection)?.apply {
            text = if (networkReady) "● Bağlantı" else "○ Bağlantı"
            setTextColor(if (networkReady) green else red)
        }
        findViewById<View>(R.id.readinessMicrophone)?.setOnClickListener {
            if (microphoneReady) {
                Toast.makeText(this, "Mikrofon izni hazır.", Toast.LENGTH_SHORT).show()
            } else {
                requestMicrophoneReadinessPermission()
            }
        }
        findViewById<View>(R.id.readinessLocation)?.setOnClickListener {
            when {
                !locationPermission -> requestLocationReadinessPermission()
                !locationEnabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                !backgroundReady -> showBackgroundLocationExplanation()
                else -> Toast.makeText(this, "Konum izinleri hazır.", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.readinessConnection)?.setOnClickListener {
            if (networkReady) {
                Toast.makeText(this, "Ağ bağlantısı mevcut.", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
        findViewById<View>(R.id.emergencyReadinessPanel)?.setOnClickListener {
            when {
                !microphoneReady -> requestMicrophoneReadinessPermission()
                !locationPermission -> requestLocationReadinessPermission()
                !locationEnabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                !backgroundReady -> showBackgroundLocationExplanation()
                !networkReady -> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                else -> Toast.makeText(this, "Temel kontroller hazır.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMicrophoneReadinessPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (AppPermissionManager.shouldOpenSettings(this, permission)) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestPermissions(arrayOf(permission), 404)
        }
    }

    private fun requestLocationReadinessPermission() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (AppPermissionManager.shouldOpenSettings(this, fine) &&
            AppPermissionManager.shouldOpenSettings(this, coarse)
        ) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestPermissions(arrayOf(fine, coarse), 402)
        }
    }

    private fun showBackgroundLocationExplanation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Arka planda konum izni")
            .setMessage(
                "Telefon kilitliyken sesli acil durum başlatıldığında konum paylaşımı " +
                    "için 'Her zaman izin ver' seçeneğini etkinleştirin. " +
                    "Bu izin, telefonun genel konum anahtarını açmaz."
            )
            .setNegativeButton("VAZGEÇ", null)
            .setPositiveButton("İZİN AYARLARI") { _, _ ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 403
                    )
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .show()
    }

    // Ilk ana ekran girisinde konum anahtari kapaliysa bir kez bilgilendir.
    private fun showInitialLocationSetupIfNeeded() {
        val prefs = getSharedPreferences("LocationOnboarding", Context.MODE_PRIVATE)
        if (prefs.getBoolean("initial_location_check_done", false)) return

        val locationManager = getSystemService(Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }

        // Bir sonraki onResume'da veya ekran acilisinda pencereyi tekrarlama.
        prefs.edit().putBoolean("initial_location_check_done", true).apply()
        if (enabled || isFinishing || isDestroyed) return

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 27, 48))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(76, 93, 121))
            }
        }
        val heading = TextView(this).apply {
            text = "KONUM HİZMETİ GEREKLİ"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 19f
            gravity = Gravity.CENTER
        }
        panel.addView(heading)
        val explanation = TextView(this).apply {
            text = "EmergencySafety'nin acil durumlarda canlı konumunuzu " +
                "yakınlarınıza gönderebilmesi için telefonunuzun konum hizmeti " +
                "açık olmalıdır.\\n\\nUygulamanın tam çalışabilmesi için " +
                "lütfen konum hizmetini açın."
            setTextColor(Color.rgb(215, 225, 238))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        }
        panel.addView(explanation)
        val dialog = Dialog(this)
        val openSettings = TextView(this).apply {
            text = "KONUMU AÇ"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 115, 92))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        panel.addView(openSettings)
        val later = TextView(this).apply {
            text = "ŞİMDİ DEĞİL"
            setTextColor(Color.rgb(192, 207, 226))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(4))
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(later)
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.65f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun isRegisteredUser(): Boolean {
        val isGuest = accountPreferences.getBoolean(
            "guest_mode",
            true
        )

        val email = accountPreferences.getString(
            "account_email",
            ""
        ).orEmpty().trim()

        val userId = accountPreferences.getString(
            "api_user_id",
            ""
        ).orEmpty().trim()

        val accessToken = try {
            SecureSessionStore.getAccessToken(applicationContext).orEmpty()
        } catch (_: Exception) {
            ""
        }

        return !isGuest &&
            email.isNotBlank() &&
            userId.isNotBlank() &&
            accessToken.isNotBlank()
    }

    private fun setupAccountButton() {
        val listeningButton =
            findViewById<androidx.cardview.widget.CardView>(
                R.id.btnToggleListening
            )

        val parent = listeningButton.parent as? LinearLayout ?: return
        val originalIndex = parent.indexOfChild(listeningButton)

        if (originalIndex < 0) return

        val density = resources.displayMetrics.density

        fun dp(value: Int): Int =
            (value * density + 0.5f).toInt()

        parent.removeView(listeningButton)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(10)
            }
        }

        listeningButton.apply {
            layoutParams =
                LinearLayout.LayoutParams(dp(68), dp(68))

            radius = 0f
            cardElevation = 0f
            setCardBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Sesli dinlemeyi aç veya kapat"

            removeAllViews()

            val powerImage = ImageView(this@MainActivity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(R.drawable.button_power)
                contentDescription = "Sesli dinleme güç butonu"
            }

            addView(powerImage)
        }

        val accountButton =
            androidx.cardview.widget.CardView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(68), dp(68))

                radius = 0f
                cardElevation = 0f
                setCardBackgroundColor(Color.TRANSPARENT)

                isClickable = true
                isFocusable = true
                contentDescription = "Hesabım"

                setOnClickListener {
                    vibrateOnClick()
                    showAccountDialog()
                }
            }

        val accountImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.button_log)
            contentDescription = "Hesabım butonu"
        }

        accountButton.addView(accountImage)

        buttonRow.addView(listeningButton)
        buttonRow.addView(accountButton)

        parent.addView(buttonRow, originalIndex)
    }

    // EMERGENCY SAFETY ÖZEL HESAP PENCERESİ
    private fun showAccountDialog() {
        val isRegistered = isRegisteredUser()

        val email = accountPreferences.getString(
            "account_email",
            ""
        ).orEmpty()

        val density = resources.displayMetrics.density

        fun dp(value: Int): Int =
            (value * density + 0.5f).toInt()

        val dialog = Dialog(this)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))

            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 27, 48))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(64, 81, 107))
            }
        }

        val logo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(72),
                dp(72)
            )
            setImageResource(R.drawable.yeni_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "EmergencySafety kurt logosu"
        }

        panel.addView(logo)

        val brand = TextView(this).apply {
            text = "EMERGENCY SAFETY"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        panel.addView(brand)

        val subtitle = TextView(this).apply {
            text = "SİZİN GÜVENLİĞİNİZ, BİZİM ÖNCELİĞİMİZ"
            textSize = 10f
            setTextColor(Color.rgb(158, 180, 208))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }

        panel.addView(subtitle)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(Color.rgb(64, 81, 107))
        }

        panel.addView(divider)

        val title = TextView(this).apply {
            text = "HESABIM"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(10))
        }

        panel.addView(title)

        if (isRegistered) {
            val userId = accountPreferences.getString("api_user_id", "").orEmpty()
            val photoFile = File(filesDir, "profile_photo_${userId}.jpg")
            val photo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Profil fotoğrafını seç"
                background = GradientDrawable().apply {
                    setColor(Color.rgb(38, 59, 80))
                    cornerRadius = dp(42).toFloat()
                    setStroke(dp(2), Color.rgb(197, 164, 77))
                }
                clipToOutline = true
                if (photoFile.isFile) {
                    setImageBitmap(BitmapFactory.decodeFile(photoFile.absolutePath))
                } else {
                    setImageResource(R.drawable.yeni_logo)
                }
                setOnClickListener { selectProfilePhoto.launch("image/*") }
            }
            profilePreview = photo
            panel.addView(photo, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            val choosePhoto = TextView(this).apply {
                text = "PROFİL FOTOĞRAFI SEÇ / DEĞİŞTİR"
                textSize = 12f
                setTextColor(Color.rgb(230, 196, 106))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(8))
                setOnClickListener { selectProfilePhoto.launch("image/*") }
            }
            panel.addView(choosePhoto)
            dialog.setOnDismissListener { profilePreview = null }
        }

        val message = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(196, 208, 224))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }

        panel.addView(message)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }

        fun createDialogButton(
            text: String,
            backgroundColor: Int,
            strokeColor: Int
        ): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true

                background = GradientDrawable().apply {
                    setColor(backgroundColor)
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), strokeColor)
                }

                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
        }

        val positiveButton = createDialogButton(
            "ÇIKIŞ YAP",
            Color.rgb(198, 40, 61),
            Color.rgb(224, 75, 91)
        )

        val negativeButton = createDialogButton(
            "KAPAT",
            Color.rgb(38, 59, 80),
            Color.rgb(82, 116, 159)
        )

        buttonRow.addView(
            positiveButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginEnd = dp(6)
            }
        )

        buttonRow.addView(
            negativeButton,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            ).apply {
                marginStart = dp(6)
            }
        )

        panel.addView(buttonRow)

        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )
            setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        if (!isRegistered) {
            title.text = "MİSAFİR KULLANIMI"

            message.text =
                "Şu anda misafir modundasınız.\n\n" +
                    "Acil durum özelliklerini kullanabilirsiniz. " +
                    "Mesajlaşma ve kayıtlı kullanıcı özellikleri için " +
                    "e-posta hesabınızla giriş yapabilirsiniz."

            positiveButton.text = "HESABA GİRİŞ"

            positiveButton.setOnClickListener {
                dialog.dismiss()
                openAccountLogin()
            }

            negativeButton.setOnClickListener {
                dialog.dismiss()
            }

        } else {
            title.text = "HESABIM"

            message.text =
                "Kayıtlı e-posta adresiniz:\n\n$email"

            positiveButton.text = "ÇIKIŞ YAP"
            negativeButton.text = "KAPAT"

            positiveButton.setOnClickListener {
                title.text = "ÇIKIŞ ONAYI"

                message.text =
                    "Bu cihazdaki güvenli oturum bilgileri silinecek " +
                        "ve giriş ekranına dönülecek.\n\n" +
                        "Hesabınız silinmez. Yalnızca bu cihazdaki " +
                        "oturumunuz kapatılır."

                positiveButton.text = "EVET, ÇIKIŞ YAP"
                negativeButton.text = "VAZGEÇ"

                positiveButton.setOnClickListener {
                    dialog.dismiss()
                    performLocalLogout()
                }

                negativeButton.setOnClickListener {
                    dialog.dismiss()
                }
            }

            negativeButton.setOnClickListener {
                dialog.dismiss()
            }
        }
    }

    private fun performLocalLogout() {
        try {
            SecureSessionStore.clearSession(applicationContext)

            val accountCleared = accountPreferences.edit()
                .remove("account_email")
                .remove("api_user_id")
                .remove("api_username")
                .remove("api_access_token")
                .putBoolean("onboarding_completed", false)
                .putBoolean("guest_mode", false)
                .commit()

            if (!accountCleared) {
                Toast.makeText(
                    this,
                    "Hesap bilgileri temizlenemedi. Tekrar dene.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            MatrixSession.accessToken = null
            MatrixSession.userId = null
            MatrixSession.deviceId = null

            val intent = Intent(
                this,
                AuthActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            startActivity(intent)
            finish()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Çıkış sırasında bir hata oluştu. Tekrar dene.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openAccountLogin() {
        val intent = Intent(
            this,
            AuthActivity::class.java
        ).apply {
            putExtra(
                AuthActivity.EXTRA_FORCE_SHOW_ACCOUNT,
                true
            )
        }

        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()

        refreshReadinessPanel()
        showInitialLocationSetupIfNeeded()

        if (::wolfBackground.isInitialized) {
            val isVoiceActive = getSharedPreferences(
                "AppSettings",
                Context.MODE_PRIVATE
            ).getBoolean("is_voice_active", false)

            updateWolfImage(isVoiceActive, false)
        }

        if (
            ::stripEmergency.isInitialized &&
            ::stripSafety.isInitialized
        ) {
            sirenStripHandler.removeCallbacks(sirenStripRunnable)
            sirenStripHandler.post(sirenStripRunnable)
        }
    }

    override fun onPause() {
        sirenStripHandler.removeCallbacks(sirenStripRunnable)
        incomingEmergencyAlert.pause()
        super.onPause()
    }

    private fun applySirenBannerOrder(swapped: Boolean) {
        if (
            !::stripEmergency.isInitialized ||
            !::stripSafety.isInitialized
        ) {
            return
        }

        if (swapped) {
            stripEmergency.setImageResource(R.drawable.banner_2)
            stripSafety.setImageResource(R.drawable.banner_1)
        } else {
            stripEmergency.setImageResource(R.drawable.banner_1)
            stripSafety.setImageResource(R.drawable.banner_2)
        }
    }

    private fun playGrowl() {
        growlPlayer?.release()
        growlPlayer = MediaPlayer.create(this, R.raw.kangal_growl)

        growlPlayer?.setOnCompletionListener { player ->
            player.release()
            if (growlPlayer === player) {
                growlPlayer = null
            }
        }

        growlPlayer?.setOnErrorListener { player, _, _ ->
            player.release()
            if (growlPlayer === player) {
                growlPlayer = null
            }
            true
        }

        growlPlayer?.start()
    }

    private fun updateWolfImage(
        active: Boolean,
        animate: Boolean = true
    ) {
        if (!::wolfBackground.isInitialized) return

        val imageRes = if (active) {
            R.drawable.wolf_active
        } else {
            R.drawable.wolf_passive
        }

        wolfBackground.animate().cancel()

        if (!animate) {
            wolfBackground.setImageResource(imageRes)
            wolfBackground.alpha = 1f
            return
        }

        wolfBackground.animate()
            .alpha(0.15f)
            .setDuration(350)
            .withEndAction {
                wolfBackground.setImageResource(imageRes)
                wolfBackground.animate()
                    .alpha(1f)
                    .setDuration(650)
                    .start()
            }
            .start()
    }

    private fun openRedMenuWithInstruction() {
        val dontShowAgain = instructionPrefs.getBoolean(
            "dont_show_red_instructions",
            false
        )

        if (dontShowAgain) {
            openRedSettings()
        } else {
            showRedInstructions()
        }
    }

    private fun openRedSettings() {
        startActivity(
            Intent(this, SettingsCategoriesActivity::class.java)
        )
    }

    private fun showRedInstructions() {
        val density = resources.displayMetrics.density

        fun dp(value: Int): Int =
            (value * density + 0.5f).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 27, 48))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(76, 93, 121))
            }
        }

        val heading = TextView(this).apply {
            text = "KIRMIZI BUTON\nKULLANIM TALİMATI"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(245, 75, 83))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        }
        panel.addView(heading)

        val scrollView = ScrollView(this).apply {
            isFillViewport = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(content)
        panel.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val instructionText = """
            ACİL DURUM AYARLARI

            Kırmızı buton, uygulamanın acil durum ayarlarına ulaşmanızı sağlar. İlk kullanımdan önce seçenekleri inceleyin.

            1. ACİL DURUM KİŞİLERİ

            Kişi ve mesaj ayarları bölümünden en fazla üç acil durum kişisi seçebilirsiniz. Kişiler yalnızca ES Messenger üzerinden karşılıklı bağlantı onayı verdiğiniz kullanıcılar arasından seçilir. Acil durum mesajlarının gönderilebilmesi için kişilerinizi önceden belirleyin.

            2. ACİL DURUM MESAJI

            Mesaj ayarlarında hazır mesajı inceleyin ve düzenleme seçeneği varsa ihtiyacınıza göre değiştirin. Değişikliklerinizi kaydedin. Mesajın gönderilmesi, uygulamanın sunduğu gönderim yöntemine ve gerekli koşullara bağlıdır.

            3. ACİL DURUM KODU

            Sesli komut ayarlarında tanımlı kodları inceleyin. Kod seçme veya değiştirme seçeneği varsa günlük konuşmalarda az kullanılan bir kelime ve iki haneli sayı tercih edebilirsiniz.

            Örnek: “Leylek 41”.

            Bu yalnızca örnektir. Uygulamanın tanıdığı kodu kullanın ve seçtiğiniz kodu güvenli bir şekilde hatırlayın.

            4. SİREN, FLAŞ VE TİTREŞİM

            Acil durum alarmı etkinleştiğinde uygulamanın siren sesi çalar, flaş yanıp söner ve komutun alındığını bildiren kısa bir titreşim oluşur. Siren çevrenizdekilerin dikkatini çekmeye ve arama-kurtarma çalışmalarında yerinizin fark edilmesine yardımcı olmayı amaçlar.

            5. SESLİ KOMUTLAR

            Sesli komut özelliği için gerekli mikrofon iznini verin. Uygulamada tanımlı komutları önceden öğrenin ve güvenli bir ortamda deneyin. Sürekli dinleme özelliğini ana ekrandaki ilgili kontrolle açıp kapatabilirsiniz.

            6. ALARMI DURDURMA

            Alarmı durdurmak için uygulamada tanımlı durdurma komutunu veya ilgili kontrolü kullanın. Bu sürümde ses kısma tuşunu yaklaşık iki saniye basılı tutmak da sireni durdurmak için kullanılabilir.

            ÖNEMLİ GÜVENLİK UYARISI

            Siren tek başına yetkililere konum veya yardım çağrısı göndermez. Konum paylaşımı, otomatik fotoğraf çekimi ve ses kaydı bu talimatta etkin özellikler olarak belirtilmemektedir.

            Uygulama resmî acil yardım hizmetlerinin yerine geçmez ve yardımın ulaşacağını garanti etmez. Hayati tehlike durumunda Türkiye'de 112 Acil Çağrı Merkezi'ni arayın.
        """.trimIndent()

        val body = TextView(this).apply {
            text = instructionText
            textSize = 15f
            setTextColor(Color.rgb(230, 236, 245))
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, 0, 0, dp(12))
        }
        content.addView(body)

        val dontShowCheckbox = CheckBox(this).apply {
            text = "Bir daha gösterme"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
            isChecked = false
        }
        panel.addView(dontShowCheckbox)

        val closeButton = TextView(this).apply {
            text = "KAPAT"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(190, 32, 43))
                cornerRadius = dp(10).toFloat()
            }
        }
        panel.addView(closeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val dialog = Dialog(this)
        dialog.setContentView(panel)
        dialog.setCancelable(false)

        closeButton.setOnClickListener {
            if (dontShowCheckbox.isChecked) {
                instructionPrefs.edit()
                    .putBoolean("dont_show_red_instructions", true)
                    .apply()
            }
            dialog.dismiss()
            openRedSettings()
        }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.7f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92f).toInt(),
                (resources.displayMetrics.heightPixels * 0.84f).toInt()
            )
        }
    }

    private fun toggleVoiceService() {
        val sharedPref =
            getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        val isCurrentlyActive =
            sharedPref.getBoolean("is_voice_active", false)

        if (isCurrentlyActive) {
            sharedPref.edit()
                .putBoolean("is_voice_active", false)
                .apply()

            stopVoiceService()
            updateWolfImage(false)
            return
        }

        ensureMicrophonePermission()
    }

    private fun ensureMicrophonePermission() {
        val permission = Manifest.permission.RECORD_AUDIO

        if (AppPermissionManager.isGranted(this, permission)) {
            activateVoiceService()
            return
        }

        if (AppPermissionManager.shouldOpenSettings(this, permission)) {
            showMicrophoneSettingsDialog()
            return
        }

        if (AppPermissionManager.shouldShowRationale(this, permission)) {
            AlertDialog.Builder(this)
                .setTitle("Mikrofon izni gerekli")
                .setMessage(
                    "Sesli acil durum komutlarını dinleyebilmek için " +
                        "mikrofon iznine ihtiyacımız var. İzin verirsen " +
                        "sesli dinleme özelliği açılacak."
                )
                .setNegativeButton("ŞİMDİ DEĞİL", null)
                .setPositiveButton("İZİN VER") { _, _ ->
                    requestMicrophonePermission()
                }
                .show()
        } else {
            requestMicrophonePermission()
        }
    }

    private fun requestMicrophonePermission() {
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MICROPHONE_PERMISSION
        )
    }

    private fun showMicrophoneSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Mikrofon izni kapalı")
            .setMessage(
                "Mikrofon izni daha önce kalıcı olarak reddedilmiş " +
                    "görünüyor. Sesli dinlemeyi kullanmak için " +
                    "Android uygulama ayarlarından mikrofon iznini açmalısın."
            )
            .setNegativeButton("VAZGEÇ", null)
            .setPositiveButton("AYARLARI AÇ") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .show()
    }

    private fun activateVoiceService() {
        getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_voice_active", true)
            .apply()

        startVoiceService()
        playGrowl()
        updateWolfImage(true)
    }

    private fun vibrateOnClick() {
        val vibrator =
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    100,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100)
        }
    }

    // İlk açılış izinleri AuthActivity tarafından istenir.
    // Burada ortak izin yöneticisiyle mikrofon izni kontrol edilir.
    private fun checkPermissionsAndStartService() {
        val hasMicrophonePermission = AppPermissionManager.isGranted(
            this,
            Manifest.permission.RECORD_AUDIO
        )

        val sharedPref =
            getSharedPreferences("AppSettings", Context.MODE_PRIVATE)

        val isVoiceActive =
            sharedPref.getBoolean("is_voice_active", false)

        if (isVoiceActive && hasMicrophonePermission) {
            startVoiceService()
        }
    }

    private fun startVoiceService() {
        val serviceIntent =
            Intent(this, VoiceTriggerService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopVoiceService() {
        val serviceIntent =
            Intent(this, VoiceTriggerService::class.java)

        stopService(serviceIntent)
    }

    private fun startSosLocationService() {
        val serviceIntent = Intent(this, SosLocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) {
                volumeDownRunnable = Runnable {
                    stopSirenService()
                    Toast.makeText(
                        this,
                        "Siren durduruldu",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                volumeDownHandler.postDelayed(
                    volumeDownRunnable!!,
                    2000
                )
            }

            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownRunnable?.let {
                volumeDownHandler.removeCallbacks(it)
            }

            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    private fun stopSirenService() {
        val serviceIntent =
            Intent(this, VoiceTriggerService::class.java).apply {
                action = "ACTION_STOP_SIREN"
            }

        startService(serviceIntent)
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

        if (requestCode == 402 || requestCode == 403 || requestCode == 404) {
            AppPermissionManager.recordPermissionResults(this, permissions, grantResults)
            refreshReadinessPanel()
        }

        if (requestCode == REQUEST_MICROPHONE_PERMISSION) {
            val permissionGranted =
                grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED

            AppPermissionManager.recordPermissionResults(
                this,
                permissions,
                grantResults
            )

            if (permissionGranted) {
                activateVoiceService()
            } else {
                Toast.makeText(
                    this,
                    "Mikrofon izni verilmedi. Sesli dinleme açılmadı.",
                    Toast.LENGTH_LONG
                ).show()

                if (
                    AppPermissionManager.shouldOpenSettings(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    )
                ) {
                    showMicrophoneSettingsDialog()
                }
            }
        }
    }

    override fun onDestroy() {
        sirenStripHandler.removeCallbacks(sirenStripRunnable)
        incomingEmergencyAlert.destroy()

        growlPlayer?.release()
        growlPlayer = null

        super.onDestroy()
    }
}
