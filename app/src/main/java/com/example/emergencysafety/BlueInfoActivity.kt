package com.example.emergencysafety

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BlueInfoActivity : AppCompatActivity() {

    private val headings = listOf(
        "Kullanım Kılavuzu",
        "Acil Durum İletişimi",
        "Gizlilik Politikası",
        "Hukuki Bilgilendirme",
        "Hikâyemiz",
        "Açık Kaynak Lisansları",
        "Uygulama Hakkında"
    )

    private val descriptions = listOf(
        "Kırmızı bölüm acil durum hazırlığı ve protokolleri içindir. Sarı bölüm, kayıtlı kullanıcıların güvenlik odaklı iletişimini sağlar. Mavi bölümde yakın sağlık kuruluşu, kolluk kuvveti ve eczane araması yapabilirsiniz.\n\nAcil durumdan önce güvenilir kişilerinizi belirleyin, izinlerinizi kontrol edin ve özellikleri güvenli bir ortamda deneyin. Telefonun şarjını ve bağlantısını kontrol edin. Sesli etkinleştirmenin her koşulda çalışacağı varsayılmamalıdır.\n\nTehlike anında önce kendi güvenliğinizi sağlayın. Uygulamayı kullanmak sizi riske atacaksa telefonla uğraşmayın. Türkiye'de resmi acil çağrı numarası 112'dir.",
        "EmergencySafety'nin sarı mesajlaşma bölümü günlük sohbet, sosyal medya veya eğlence amacıyla değil; acil durumlarda ve acil durum hazırlığında güvenilir kişilerle iletişim kurmak için tasarlanmıştır.\n\nLütfen bu alanı yalnızca acil durumlarla ilgili iletişim ve hazırlık amacıyla kullanın. Gereksiz mesajlar, önemli yardım çağrılarının fark edilmesini zorlaştırabilir.\n\nMesaj gönderildi bilgisi, mesajın mutlaka okunduğu veya yardımın ulaştığı anlamına gelmez. Resmi acil yardım hizmetleriyle iletişimin yerini tutmaz.",
        "GİZLİLİK BİLGİLENDİRMESİ — TASLAK\n\nEmergencySafety, işlevleri için gerekli verileri mümkün olduğunca sınırlı tutmayı hedefler. E-posta ile hesap doğrulaması yapılır. Mesajlaşma hizmetinin çalışması için sunucuda hesap, bağlantı, mesaj ve teslim/okunma durumlarına ilişkin kayıtlar bulunabilir. Bildirimler için cihaz bildirim belirteçleri işlenebilir.\n\nKonum, mikrofon ve kamera özellikleri ilgili cihaz izinlerine ve kullanıcının başlattığı güvenlik işlevlerine bağlıdır. Firebase bildirimleri ve e-posta gönderimi gibi üçüncü taraf altyapılar kullanılmaktadır. Bu nedenle hiçbir verinin üçüncü taraf hizmetlerce işlenmediği söylenemez.\n\nMesajların uçtan uca şifrelendiği şu aşamada garanti edilmemektedir. Veri saklama süreleri, silme yöntemleri ve hizmet sağlayıcıların ayrıntıları doğrulanarak yayın öncesinde tamamlanacak resmi gizlilik politikası ayrıca yayımlanacaktır. Bu metin nihai KVKK aydınlatma metni değildir.",
        "EmergencySafety, resmi acil çağrı, ambulans, sağlık veya kolluk hizmeti değildir. Yardım istemeyi kolaylaştıran tamamlayıcı bir güvenlik aracıdır.\n\nİnternet kesintisi, pil durumu, cihaz ve işletim sistemi kısıtlamaları, eksik izinler veya üçüncü taraf hizmet arızaları nedeniyle sesli komutlar algılanamayabilir; mesajlar, bildirimler ve konum bilgileri gecikebilir ya da ulaşmayabilir.\n\nUygulama, her koşulda kesintisiz çalışmayı veya yardımın mutlaka ulaşmasını garanti etmez. Acil tehlikede uygun olduğunda 112'yi arayın.\n\nUygulamayı başkalarının mahremiyetini ihlal etmek veya hukuka aykırı kayıt yapmak için kullanmayın. Bu açıklama kanunen sınırlandırılamayan hakları ortadan kaldırmaz. Yayın öncesi hukuki inceleme yapılacaktır.",
        "Bir fikir, bir cep telefonu ve yapay zekâ…\n\nEmergencySafety, tehlike anında telefonuna ulaşamayan bir insanın nasıl yardım isteyebileceği sorusuyla başlayan bir fikirden doğdu.\n\nGeliştiricisi, 44 yaşında ve yazılım geliştirme bilgisi olmadan, yalnızca bir cep telefonu ve ChatGPT yardımıyla bu fikri gerçek bir Android uygulamasına dönüştürmeye başladı.\n\nDenemeler, hatalar, tekrar yapılan derlemeler ve iki telefonla gerçekleştirilen testler bu yolculuğun parçası oldu. Hedef, günlük bir sohbet uygulaması değil; ihtiyaç anında insanlara yardımcı olabilecek, şeffaf ve güvenlik odaklı bir araç geliştirmek.\n\nNot: Hâlâ kodlama bilmiyorum.",
        "EmergencySafety, Vosk, AndroidX, Google/Firebase, Trixnity, Vodozemac ve Ktor gibi yazılım bileşenlerinden yararlanır.\n\nHer bileşenin telif hakkı bildirimi ve lisans koşulları ayrı ayrı doğrulanacaktır. Bu ekran şu an bilgilendirme amaçlıdır; zorunlu üçüncü taraf lisans metinlerinin yerine geçmez. Yayın sürümünden önce eksiksiz lisans ve atıf listesi eklenecektir.",
        "EmergencySafety\n\nGüvenlik odaklı acil durum hazırlığı, yardım çağrısı ve iletişim uygulaması.\n\nKırmızı: Acil durum protokolleri\nSarı: Acil durum iletişimi\nMavi: Yakındaki hizmetler ve bilgi merkezi\n\nBu bölümdeki politika ve hukuki metinler yayın öncesi taslak niteliğindedir. Uygulamanın gerçek teknik işleyişi ve mevzuat gereklilikleri doğrulanarak güncellenecektir."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blue_info)

        findViewById<ImageView>(R.id.blueButton1).setOnClickListener {
            openNearbySearch("hastane acil servis")
        }
        findViewById<ImageView>(R.id.blueButton2).setOnClickListener {
            openNearbySearch("polis merkezi jandarma")
        }
        findViewById<ImageView>(R.id.blueButton3).setOnClickListener {
            openNearbySearch("eczane")
        }
        findViewById<ImageView>(R.id.blueInfoCenterButton).setOnClickListener {
            showInfoCenter()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun panelBackground(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(24, 39, 61), Color.rgb(8, 19, 35))
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), Color.rgb(95, 139, 182))
        }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(Color.rgb(224, 235, 249))
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun showInfoCenter() {
        val dialog = Dialog(this)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = panelBackground()
        }
        val title = text("BİLGİ VE GÜVEN MERKEZİ", 19f, true).apply {
            gravity = Gravity.CENTER
        }
        content.addView(title)
        content.addView(text("Güvenlik • Şeffaflık • Bilinçli kullanım", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(18))
        })

        headings.forEachIndexed { index, heading ->
            val row = text(heading + "   ›", 16f, true).apply {
                background = panelBackground()
                setPadding(dp(15), dp(17), dp(12), dp(17))
                isClickable = true
                isFocusable = true
                contentDescription = heading
                setOnClickListener { showInfoDetail(heading, descriptions[index]) }
            }
            content.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) })
        }
        // Gecici Matrix test girisi - yayin oncesinde kaldirilacak.
        content.addView(text("MATRIX TEST MERKEZİ   ›", 16f, true).apply {
            background = panelBackground()
            setPadding(dp(15), dp(17), dp(12), dp(17))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                startActivity(Intent(this@BlueInfoActivity, MatrixTestActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scroll.addView(content)
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun showInfoDetail(heading: String, body: String) {
        val dialog = Dialog(this)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(22))
            background = panelBackground()
        }
        content.addView(text(heading, 20f, true))
        content.addView(text(body, 15f).apply {
            setPadding(0, dp(16), 0, dp(18))
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        content.addView(text("‹ Bilgi merkezine dön", 14f, true).apply {
            setPadding(dp(5), dp(12), dp(5), dp(12))
            setOnClickListener { dialog.dismiss() }
        })
        scroll.addView(content)
        dialog.setContentView(scroll)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
    }

    private fun openNearbySearch(query: String) {
        val uri = Uri.parse(
            "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query)
        )
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Harita açılamadı. İnternet bağlantınızı kontrol edin.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
