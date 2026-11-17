package com.example.pstranslate.phone

import android.app.Activity
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors

/** Aşama 1: TV'yi bulur, kodla eşleşir, yazılan metni TV'ye altyazı olarak yollar. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var code: EditText
    private lateinit var msg: EditText
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var socket: Socket? = null
    private var out: PrintWriter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        status = TextView(this).apply { text = "Bağlı değil"; textSize = 18f }
        code = EditText(this).apply { hint = "TV'deki 4 haneli kod"; inputType = InputType.TYPE_CLASS_NUMBER }
        val connect = Button(this).apply { text = "TV'ye bağlan"; setOnClickListener { startDiscovery() } }
        msg = EditText(this).apply { hint = "Test altyazısı yaz"; }
        val send = Button(this).apply {
            text = "TV'ye gönder"
            setOnClickListener { send("SUB " + msg.text.toString().replace("\n", "\\n")) }
        }
        val clear = Button(this).apply { text = "Altyazıyı temizle"; setOnClickListener { send("CLEAR") } }
        listOf(status, code, connect, msg, send, clear).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun setStatus(t: String) = ui.post { status.text = t }

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
                            @Suppress("DEPRECATION")
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
                socket?.close()
                val s = Socket(host, port)
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val w = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), true)
                w.println("HELLO $c")
                if (r.readLine() == "OK") {
                    socket = s; out = w
                    setStatus("TV'ye bağlandı ✔")
                    stopDiscovery()
                } else {
                    s.close()
                    setStatus("Kod yanlış")
                }
            } catch (e: Exception) { setStatus("Bağlantı hatası: ${e.message}") }
        }
    }

    private fun send(line: String) {
        io.execute {
            try { out?.println(line) } catch (e: Exception) { setStatus("Gönderilemedi: ${e.message}") }
        }
    }

    override fun onDestroy() {
        stopDiscovery()
        try { socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
