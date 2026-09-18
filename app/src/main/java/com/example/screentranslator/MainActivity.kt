package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvCurrentKey: TextView
    private lateinit var tvCurrentModel: TextView
    private lateinit var btnStart: Button
    private lateinit var btnSettings: Button
    private lateinit var btnExit: Button
    private var isTranslating = false

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateComboText()
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

        btnStart.setOnClickListener {
            if (!isTranslating) {
                enableFlow()
            } else {
                disableFlow()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnExit.setOnClickListener {
            getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("uiOff", true).apply()
            sendBroadcast(Intent("com.example.screentranslator.EXIT"))
            Toast.makeText(this, "সম্পূর্ণ বন্ধ হয়েছে। ব্যাটারি খরচ হবে না।", Toast.LENGTH_LONG).show()
            finish()
        }

        updateUI()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains("TranslatorAccessibilityService")
    }

    private fun enableFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "ওভারলে পারমিশন দিন", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )
            startActivityForResult(intent, 1002)
            return
        }

        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("uiOff", false).apply()

        if (!accessibilityOn()) {
            Toast.makeText(this, "তালিকা থেকে Screen Translator খুঁজে On করুন", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            Toast.makeText(this, "ফ্লোটিং বাটন এসেছে! যে কোনো অ্যাপে গিয়ে চাপ দিন", Toast.LENGTH_LONG).show()
        }
        isTranslating = true
        updateUI()
    }

    private fun disableFlow() {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("uiOff", true).apply()
        Toast.makeText(this, "লুকানো হয়েছে। আবার চালু করতে শুরু বাটনে চাপুন", Toast.LENGTH_SHORT).show()
        isTranslating = false
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1002 && Settings.canDrawOverlays(this)) {
            enableFlow()
        }
    }

    private fun updateUI() {
        if (isTranslating) {
            btnStart.text = getString(R.string.stop_translation)
            tvStatus.text = getString(R.string.status_translating)
        } else {
            btnStart.text = getString(R.string.start_translation)
            tvStatus.text = getString(R.string.status_idle)
        }
        updateComboText()
    }

    private fun updateComboText() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("currentKey", "None") ?: "None"
        val currentModel = prefs.getString("currentModel", "None") ?: "None"
        val lastError = prefs.getString("lastError", "") ?: ""
        tvCurrentKey.text = "Key: " + currentKey
        tvCurrentModel.text = "Model: " + currentModel
        if (lastError.isNotBlank()) {
            tvStatus.text = lastError
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
