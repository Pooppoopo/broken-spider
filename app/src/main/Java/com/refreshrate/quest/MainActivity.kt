package com.refreshrate.quest

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlin.concurrent.thread

/**
 * Sets the Quest refresh rate through the headset's own Wireless Debugging.
 * No service, nothing in the background: it runs one command and closes itself.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var hostBox: EditText
    private lateinit var pairPortBox: EditText
    private lateinit var codeBox: EditText
    private lateinit var portBox: EditText
    private lateinit var customBox: EditText
    private lateinit var cmdBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep our adb key between launches so pairing only has to happen once.
        KadbCert.configure(
            store = OkioFilePrivateKeyStore("${filesDir.absolutePath}/adbkey.pem".toPath())
        )
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        status = label("Turn on Wireless Debugging in Developer settings, then pair once.", 18)
        root.addView(status)

        root.addView(label("1) Pair (first time only)", 20))
        hostBox = edit("Host", false).apply { setText(prefs.getString("host", "127.0.0.1")) }
        root.addView(hostBox)
        pairPortBox = edit("Pairing port", true)
        root.addView(pairPortBox)
        codeBox = edit("6-digit pairing code", true)
        root.addView(codeBox)
        root.addView(button("Pair") { pair() })

        root.addView(label("2) Set refresh rate", 20))
        portBox = edit("Connect port (shown in Wireless Debugging)", true).apply {
            val saved = prefs.getInt("port", 0)
            if (saved > 0) setText(saved.toString())
        }
        root.addView(portBox)

        val rateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (hz in intArrayOf(72, 80, 90, 120)) {
            rateRow.addView(
                button("$hz Hz") { setRate(hz) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        root.addView(rateRow)

        val customRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        customBox = edit("Custom Hz (60 or higher)", true)
        customRow.addView(
            customBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        customRow.addView(button("Set") {
            val hz = customBox.text.toString().trim().toIntOrNull()
            when {
                hz == null -> status.text = "Enter a number. Nothing executed."
                hz < 60 -> status.text = "Below 60 Hz. Nothing executed."
                else -> setRate(hz)
            }
        })
        root.addView(customRow)

        root.addView(label("3) Shell command", 20))
        cmdBox = edit("adb shell command", false).apply { setSingleLine(true) }
        root.addView(cmdBox)
        root.addView(button("Run") {
            val c = cmdBox.text.toString().trim()
            if (c.isNotEmpty()) runShell(c, null)
        })

        output = label("", 14).apply { setTextIsSelectable(true) }
        root.addView(output)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(text: String, sp: Int) = TextView(this).apply {
        this.text = text
        textSize = sp.toFloat()
    }

    private fun edit(hint: String, number: Boolean) = EditText(this).apply {
        this.hint = hint
        textSize = 18f
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 18f
        setOnClickListener { onClick() }
    }

    private fun host() = hostBox.text.toString().trim().ifEmpty { "127.0.0.1" }

    private fun pair() {
        val port = pairPortBox.text.toString().trim().toIntOrNull()
        val code = codeBox.text.toString().trim()
        if (port == null || code.length != 6) {
            status.text = "Enter the pairing port and the 6-digit code."
            return
        }
        val host = host()
        status.text = "Pairing..."
        thread {
            try {
                runBlocking { Kadb.pair(host, port, code) }
                runOnUiThread {
                    status.text = "Paired. Now enter the connect port and pick a rate."
                    codeBox.setText("")
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Pairing failed: $e" }
            }
        }
    }

    private fun setRate(hz: Int) {
        runShell(
            "setprop debug.oculus.refreshRate $hz; getprop debug.oculus.refreshRate",
            hz.toString()
        )
    }

    /** Runs one shell command. If [expect] matches the output, the app closes itself. */
    private fun runShell(cmd: String, expect: String?) {
        val port = portBox.text.toString().trim().toIntOrNull()
        if (port == null) {
            status.text = "Enter the connect port from Wireless Debugging."
            return
        }
        val host = host()
        getSharedPreferences("p", Context.MODE_PRIVATE).edit()
            .putString("host", host).putInt("port", port).apply()
        status.text = "Connecting..."
        thread {
            try {
                Kadb.create(host, port, 5000, 30000).use { kadb ->
                    val result = kadb.shell(cmd)
                    val out = result.allOutput.trim()
                    runOnUiThread {
                        if (expect != null && out == expect) {
                            finishAndRemoveTask()
                        } else {
                            status.text =
                                if (expect != null) "Unexpected result (see output)."
                                else "Done (exit ${result.exitCode})."
                            output.text = out
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Failed: $e" }
            }
        }
    }
}
