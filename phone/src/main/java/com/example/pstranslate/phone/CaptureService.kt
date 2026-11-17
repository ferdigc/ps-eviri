package com.example.pstranslate.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Ekranı yakalar, İngilizce yazıyı okur, Türkçeye çevirir, TV'ye yollar. */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var autoBtn: TextView? = null
    private var statusView: TextView? = null
    private var busyStart = 0L

    private var auto = false
    private val busy = AtomicBoolean(false)
    private var lastText = ""
    private var lastTranslation = ""
    private var lastSent = 0L
    private var emptyCount = 0
    private var width = 0
    private var height = 0
    private var dpi = 0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    private val autoTick = object : Runnable {
        override fun run() {
            if (!auto) return
            captureOnce(true)
            main.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null || projection != null) { if (projection == null) stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        val cb = object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }
        projectionCallback = cb
        p.registerCallback(cb, main)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupCapture()
        createPanel()
        translator.downloadModelIfNeeded()
            .addOnFailureListener { toast("Çeviri modeli indirilemedi, internete bağlı mısın?") }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "Ekran çevirisi", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cap")
            .setContentTitle("PS Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return dm
    }

    private fun setupCapture() {
        val dm = realMetrics()
        width = dm.widthPixels; height = dm.heightPixels; dpi = dm.densityDpi
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        vdisplay = projection!!.createVirtualDisplay(
            "pscap", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
    }

    /** Ekran döndüyse yakalama boyutunu günceller. Değiştiyse true döner. */
    private fun ensureSize(): Boolean {
        val dm = realMetrics()
        if (dm.widthPixels == width && dm.heightPixels == height) return false
        width = dm.widthPixels; height = dm.heightPixels; dpi = dm.densityDpi
        reader?.close()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        vdisplay?.resize(width, height, dpi)
        vdisplay?.surface = reader!!.surface
        return true
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(28, 22, 28, 22)
        background = GradientDrawable().apply { cornerRadius = 24f; setColor(Color.argb(160, 20, 20, 20)) }
        setOnClickListener { onClick() }
    }

    private fun createPanel() {
        val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 8) }
        val sv = TextView(this).apply {
            text = "Hazır (v0.3)"
            textSize = 11f
            setTextColor(Color.YELLOW)
            maxWidth = 500
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply { cornerRadius = 16f; setColor(Color.argb(160, 20, 20, 20)) }
        }
        statusView = sv
        p.addView(sv, lp)
        p.addView(button("Çevir") { captureOnce(false) }, lp)
        val a = button("Oto: KAPALI") { toggleAuto() }
        autoBtn = a
        p.addView(a, lp)
        p.addView(button("Kapat") { stopSelf() }, lp)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        wm?.addView(p, params)
        panel = p
    }

    private fun toggleAuto() {
        auto = !auto
        autoBtn?.text = if (auto) "Oto: AÇIK" else "Oto: KAPALI"
        main.removeCallbacks(autoTick)
        if (auto) main.post(autoTick)
    }

    private fun toast(t: String) {
        statusView?.text = t
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun blankPanel(bmp: Bitmap) {
        val p = panel ?: return
        val loc = IntArray(2)
        p.getLocationOnScreen(loc)
        Canvas(bmp).drawRect(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + p.width).toFloat(), (loc[1] + p.height).toFloat(),
            Paint().apply { color = Color.BLACK }
        )
    }

    private fun captureOnce(isAuto: Boolean) {
        if (!isAuto) toast("Basıldı")
        if (!Link.connected) { if (!isAuto) toast("TV bağlantısı yok"); return }
        val now0 = System.currentTimeMillis()
        if (!busy.compareAndSet(false, true)) {
            if (now0 - busyStart < 10000) { if (!isAuto) toast("Önceki işlem sürüyor…"); return }
            toast("Takılan işlem sıfırlandı")
        }
        busyStart = now0
        if (ensureSize()) { busy.set(false); if (!isAuto) toast("Ekran boyutu değişti, tekrar bas"); return }
        val img = reader?.acquireLatestImage()
        if (img == null) { busy.set(false); if (!isAuto) toast("Görüntü alınamadı, tekrar bas"); return }
        val bottomOnly = getSharedPreferences("p", MODE_PRIVATE).getBoolean("bottom", true)
        val bmp = try {
            val plane = img.planes[0]
            val pad = plane.rowStride - plane.pixelStride * img.width
            val full = Bitmap.createBitmap(img.width + pad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            blankPanel(full)
            val top = if (bottomOnly) img.height * 65 / 100 else 0
            Bitmap.createBitmap(full, 0, top, img.width, img.height - top)
        } finally {
            img.close()
        }
        if (!isAuto && isBlack(bmp)) {
            busy.set(false)
            toast("Yakalanan görüntü SİYAH: Remote Play yakalamayı engelliyor olabilir")
            return
        }
        if (!isAuto) toast("Okunuyor…")
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { vt ->
                val raw = vt.textBlocks.joinToString("\n") { it.text.replace("\n", " ") }.trim()
                handleText(raw, isAuto)
            }
            .addOnFailureListener { e -> busy.set(false); toast("Okuma hatası: ${e.message}") }
    }

    private fun isBlack(b: Bitmap): Boolean {
        var maxSum = 0
        for (i in 0 until 20) for (j in 0 until 20) {
            val c = b.getPixel(i * (b.width - 1) / 19, j * (b.height - 1) / 19)
            maxSum = maxOf(maxSum, Color.red(c) + Color.green(c) + Color.blue(c))
        }
        return maxSum < 30
    }

    private fun handleText(raw: String, isAuto: Boolean) {
        val letters = raw.count { it.isLetter() }
        val alreadyTurkish = Regex("[ğĞıİşŞ]").containsMatchIn(raw)
        val text = raw.take(300)
        if (letters < 3 || alreadyTurkish) {
            if (isAuto) {
                emptyCount++
                if (emptyCount == 2 && lastText.isNotEmpty()) { Link.send("CLEAR"); lastText = "" }
            } else toast("Yazı bulunamadı. Okunan: \"${raw.take(40)}\" (harf sayısı: $letters)")
            busy.set(false)
            return
        }
        emptyCount = 0
        val now = System.currentTimeMillis()
        if (isAuto && text == lastText) {
            if (lastTranslation.isNotEmpty() && now - lastSent > 6000) {
                lastSent = now
                Link.send("SUB " + lastTranslation.replace("\n", "\\n"))
            }
            busy.set(false)
            return
        }
        lastText = text
        if (!isAuto) toast("Çevriliyor…")
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { tr ->
                        lastTranslation = tr
                        lastSent = System.currentTimeMillis()
                        Link.send("SUB " + tr.replace("\n", "\\n"))
                        if (!isAuto) toast("Gönderildi: ${tr.take(40)}")
                        busy.set(false)
                    }
                    .addOnFailureListener { e -> busy.set(false); toast("Çeviri hatası: ${e.message}") }
            }
            .addOnFailureListener { e -> toast("Çeviri modeli yok, internete bağlan: ${e.message}"); busy.set(false) }
    }

    override fun onDestroy() {
        auto = false
        main.removeCallbacks(autoTick)
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        try { vdisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        projectionCallback?.let { projection?.unregisterCallback(it) }
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { recognizer.close() } catch (_: Exception) {}
        try { translator.close() } catch (_: Exception) {}
        Link.send("CLEAR")
        super.onDestroy()
    }
}
