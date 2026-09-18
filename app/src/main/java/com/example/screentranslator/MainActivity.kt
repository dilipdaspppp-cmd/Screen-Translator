package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
    private lateinit var mediaProjectionManager: MediaProjectionManager
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

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart.setOnClickListener {
            if (!isTranslating) {
                checkPermissionsAndStart()
            } else {
                stopTranslation()
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateUI()
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "অনুগ্রহ করে ওভারলে পারমিশন দিন", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            return
        }

        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, SCREEN_CAPTURE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            SCREEN_CAPTURE_REQUEST -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startTranslation(resultCode, data)
                } else {
                    Toast.makeText(this, "স্ক্রিন ক্যাপচার বাতিল হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
            OVERLAY_PERMISSION_REQUEST -> {
                if (Settings.canDrawOverlays(this)) {
                    checkPermissionsAndStart()
                }
            }
        }
    }

    private fun startTranslation(resultCode: Int, data: Intent) {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val keys = listOf(
            prefs.getString("key1", "") ?: "",
            prefs.getString("key2", "") ?: "",
            prefs.getString("key3", "") ?: "",
            prefs.getString("key4", "") ?: "",
            prefs.getString("key5", "") ?: ""
        ).filter { it.isNotBlank() }

        if (keys.isEmpty()) {
            Toast.makeText(this, "অনুগ্রহ করে সেটিংসে অন্তত একটি API key দিন", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, TranslationService::class.java).apply {
            action = "START_TRANSLATION"
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        startForegroundService(serviceIntent)
        isTranslating = true
        updateUI()
    }

    private fun stopTranslation() {
        val serviceIntent = Intent(this, TranslationService::class.java).apply {
            action = "STOP_TRANSLATION"
        }
        startService(serviceIntent)
        isTranslating = false
        updateUI()
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
        tvCurrentKey.text = "Key: " + currentKey
        tvCurrentModel.text = "Model: " + currentModel
    }

    override fun onResume() {
        super.onResume()
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
    }

    companion object {
        private const val SCREEN_CAPTURE_REQUEST = 1001
        private const val OVERLAY_PERMISSION_REQUEST = 1002
    }
}
