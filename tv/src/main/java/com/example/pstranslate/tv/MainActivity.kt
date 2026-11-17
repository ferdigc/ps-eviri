package com.example.pstranslate.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val code = prefs.getString("code", null)
            ?: (1000..9999).random().toString().also { prefs.edit().putString("code", it).apply() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 80, 80, 80)
        }
        root.addView(TextView(this).apply { text = "Eşleşme kodu"; textSize = 28f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = code; textSize = 80f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply {
            text = "Telefondaki uygulamaya bu kodu gir. Telefon ve TV aynı Wi-Fi'de olmalı."
            textSize = 20f; gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "1) Üstte gösterme (overlay) iznini ver"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "2) Altyazı alıcısını başlat"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Önce overlay iznini ver (README'deki adb komutu da olur)", Toast.LENGTH_LONG).show()
                } else {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                    Toast.makeText(this@MainActivity, "Alıcı çalışıyor", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(root)
    }
}
