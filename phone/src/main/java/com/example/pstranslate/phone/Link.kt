package com.example.pstranslate.phone

import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors

/** TV ile olan bağlantıyı uygulama genelinde tutar. */
object Link {
    @Volatile var socket: Socket? = null
    @Volatile var out: PrintWriter? = null
    private val io = Executors.newSingleThreadExecutor()

    val connected: Boolean get() = out != null && socket?.isClosed == false

    fun send(line: String) {
        io.execute {
            val o = out
            if (o == null) return@execute
            o.println(line)
            if (o.checkError()) {
                try { socket?.close() } catch (_: Exception) {}
                out = null
            }
        }
    }
}
