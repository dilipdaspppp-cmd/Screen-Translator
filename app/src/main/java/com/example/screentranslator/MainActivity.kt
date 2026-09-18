package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var tvStatus: TextView? = null
    private var tvCurrentKey: TextView? = null
    private var tvCurrentModel: TextView? = null
    private var btnStart: Button? = null
    private var btnSettings: Button? = null
    private var btnExit: Button? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateUI()
            pollHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvCurrentKey = findViewById(R.id.tvCurrentKey)
        tvCurrentModel = findViewById(R.id.tvCurrentModel)
        btnStart = findViewById(R.id.btnStart)
        btnSettings = findViewById(R.id.btnSettings)
        btnExit = findViewById(R.id.btnExit)

        btnStart?.setOnClickListener {
            if (isRunning()) startOrStop(false) else startOrStop(true)
        }

        btnSettings?.setOnClickListener {
            try {
                startActivity(Intent(this, SettingsActivity::class.java))
            } catch (e: Exception) { }
        }

        btnExit?.setOnClickListener {
            prefs().edit().putBoolean("uiOff", true).apply()
            val i = Intent("com.example.screentranslator.EXIT")
            i.setPackage(packageName)
            sendBroadcast(i)
            Toast.makeText(this, getString(R.string.msg_exit), Toast.LENGTH_LONG).show()
            updateUI()
        }

        updateUI()
    }

    private fun prefs() = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    private fun accessibilityOn(): Boolean {
        try {
            val expected = packageName + "/" + TranslatorAccessibilityService::class.java.name
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val comp = splitter.next()
                if (comp.equals(expected, true)) return true
                if (comp.contains("TranslatorAccessibilityService")) return true
            }
        } catch (e: Exception) { }
        return false
    }

    private fun isRunning(): Boolean {
        val off = prefs().getBoolean("uiOff", false)
        return accessibilityOn() && !off
    }

    private fun startOrStop(start: Boolean) {
        prefs().edit().putBoolean("uiOff", !start).apply()
        val sync = Intent("com.example.screentranslator.SYNC")
        sync.setPackage(packageName)
        sendBroadcast(sync)

        if (start) {
            if (!accessibilityOn()) {
                Toast.makeText(this, getString(R.string.msg_need_accessibility), Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) { }
            } else {
                Toast.makeText(this, getString(R.string.msg_ready), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.msg_stopped), Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }

    private fun updateUI() {
        val running = isRunning()
        if (running) {
            btnStart?.text = getString(R.string.stop_translation)
            tvStatus?.text = getString(R.string.status_translating)
        } else {
            btnStart?.text = getString(R.string.start_translation)
            tvStatus?.text = getString(R.string.status_idle)
        }

        val p = prefs()
        val currentKey = p.getString("currentKey", "None") ?: "None"
        val currentModel = p.getString("currentModel", "None") ?: "None"
        val lastError = p.getString("lastError", "") ?: ""
        tvCurrentKey?.text = "Key: " + currentKey
        tvCurrentModel?.text = "Model: " + currentModel
        if (lastError.isNotBlank()) {
            tvStatus?.text = lastError
        }
    }

    override fun onResume() {
        super.onResume()
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }
}
