package com.example.pstranslate.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var code: EditText
    private lateinit var msg: EditText
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        status = TextView(this).apply { text = if (Link.connected) "TV'ye bağlandı ✔" else "Bağlı değil"; textSize = 18f }
        code = EditText(this).apply { hint = "TV'deki 4 haneli kod"; inputType = InputType.TYPE_CLASS_NUMBER }
        val connect = Button(this).apply { text = "1) TV'ye bağlan"; setOnClickListener { startDiscovery() } }
        val bottom = CheckBox(this).apply {
            text = "Sadece ekranın alt kısmındaki altyazıyı çevir"
            isChecked = prefs.getBoolean("bottom", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("bottom", v).apply() }
        }
        val start = Button(this).apply { text = "2) Çeviriyi başlat (yüzen düğmeler)"; setOnClickListener { startTranslate() } }
        val stop = Button(this).apply {
            text = "Çeviriyi durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        }
        msg = EditText(this).apply { hint = "Test altyazısı yaz" }
        val send = Button(this).apply {
            text = "Test: TV'ye gönder"
            setOnClickListener { Link.send("SUB " + msg.text.toString().replace("\n", "\\n")) }
        }
        val clear = Button(this).apply { text = "Altyazıyı temizle"; setOnClickListener { Link.send("CLEAR") } }
        val ver = TextView(this).apply { text = "Sürüm 0.4"; textSize = 12f }
        listOf(ver, status, code, connect, bottom, start, stop, msg, send, clear).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun setStatus(t: String) = ui.post { status.text = t }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun startTranslate() {
        if (!Link.connected) { toast("Önce TV'ye bağlan"); return }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        if (!Settings.canDrawOverlays(this)) {
            toast("Diğer uygulamaların üzerinde gösterme iznini ver, sonra tekrar bas")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY
            )
            return
        }
        askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY && Settings.canDrawOverlays(this)) askProjection()
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("data", data)
            )
            toast("Hazır. PS Remote Play'i aç, yüzen düğmelerle çevir.")
        }
    }

    private fun startDiscovery() {
        stopDiscovery()
        setStatus("TV aranıyor…")
        val m = getSystemService(NSD_SERVICE) as NsdManager
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceType.startsWith("_pstranslate._tcp")) {
                    @Suppress("DEPRECATION")
                    m.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) { setStatus("TV çözümlenemedi ($e)") }
                        override fun onServiceResolved(i: NsdServiceInfo) {
                            connectTo(i.host.hostAddress ?: return, i.port)
                        }
                    })
                }
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { setStatus("Arama başlamadı ($e)") }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        discovery = l
        m.discoverServices("_pstranslate._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discovery = null
    }

    private fun connectTo(host: String, port: Int) {
        val c = code.text.toString().trim()
        io.execute {
            try {
                Link.socket?.close()
                val s = Socket(host, port)
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val w = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                w.println("HELLO $c")
                if (r.readLine() == "OK") {
                    Link.socket = s
                    Link.out = w
                    setStatus("TV'ye bağlandı ✔")
                    stopDiscovery()
                } else {
                    s.close()
                    setStatus("Kod yanlış")
                }
            } catch (e: Exception) { setStatus("Bağlantı hatası: ${e.message}") }
        }
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    companion object {
        const val REQ_OVERLAY = 2
        const val REQ_CAPTURE = 1
    }
}
