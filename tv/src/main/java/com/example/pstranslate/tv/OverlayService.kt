package com.example.pstranslate.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/** Telefondan gelen altyazıyı TV ekranının üstüne çizer. Protokol: satır tabanlı TCP. */
class OverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var wm: WindowManager? = null
    private var subtitle: TextView? = null
    private val hide = Runnable { subtitle?.visibility = View.GONE }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_STICKY
        startAsForeground()
        createOverlay()
        Thread { serve() }.start()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rx", "Altyazı alıcısı", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rx")
            .setContentTitle("PS Çeviri alıcısı çalışıyor")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            gravity = Gravity.CENTER
            setPadding(40, 16, 40, 16)
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM; y = 60 }
        wm?.addView(tv, lp)
        subtitle = tv
    }

    private fun show(text: String) {
        main.post {
            val v = subtitle ?: return@post
            main.removeCallbacks(hide)
            if (text.isBlank()) { v.visibility = View.GONE; return@post }
            v.text = text
            v.visibility = View.VISIBLE
            main.postDelayed(hide, 8000)
        }
    }

    private fun serve() {
        val ss = try { ServerSocket(0) } catch (e: IOException) { return }
        server = ss
        register(ss.localPort)
        while (!ss.isClosed) {
            try {
                val s = ss.accept()
                Thread { handle(s) }.start()
            } catch (e: IOException) { break }
        }
    }

    private fun handle(s: Socket) {
        val code = getSharedPreferences("p", MODE_PRIVATE).getString("code", "") ?: ""
        s.use {
            try {
                s.soTimeout = 5000
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val w = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                val hello = r.readLine() ?: return
                if (hello != "HELLO $code") { w.println("NO"); return }
                w.println("OK")
                s.soTimeout = 0
                while (true) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("SUB ") -> show(line.substring(4).replace("\\n", "\n"))
                        line == "CLEAR" -> show("")
                    }
                }
            } catch (e: IOException) { /* bağlantı koptu */ }
        }
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "PSCeviriTV"
            serviceType = "_pstranslate._tcp"
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    override fun onDestroy() {
        try { regListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
        subtitle?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
