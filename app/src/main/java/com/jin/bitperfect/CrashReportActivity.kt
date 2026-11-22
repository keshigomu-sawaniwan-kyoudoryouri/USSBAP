package com.jin.bitperfect

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class CrashReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reportPath = intent.getStringExtra("report_path") ?: return finish()
        val file = File(reportPath)
        if (!file.exists()) return finish()

        title = "Crash Report"
        val textView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            text = file.readText()
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        setContentView(scrollView)
    }
}
