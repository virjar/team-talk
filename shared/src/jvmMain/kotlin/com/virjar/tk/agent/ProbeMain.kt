package com.virjar.tk.agent

import java.net.HttpURLConnection
import java.net.URL

fun main() {
    val c = URL("http://127.0.0.1:8600/v1/status").openConnection() as HttpURLConnection
    c.setConnectTimeout(3000)
    println("code=" + c.responseCode)
}
