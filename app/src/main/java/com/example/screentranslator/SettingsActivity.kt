package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class SettingsActivity : Activity() {
    private val modelNames = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3-flash-preview",
        "gemini-3.8-flash",
        "gemini-3.6-flash"
    )

    private var tvSpeed: TextView? = null
    private var tvNow: TextView? = null
    private var tvHistory: TextView? = null
    private var lastLog: String? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshLive()
            pollHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etKey1 = findViewById<EditText>(R.id.etKey1)
        val etKey2 = findViewById<EditText>(R.id.etKey2)
        val etKey3 = findViewById<EditText>(R.id.etKey3)
        val etKey4 = findViewById<EditText>(R.id.etKey4)
        val etKey5 = findViewById<EditText>(R.id.etKey5)

        val cbAuto = findViewById<CheckBox>(R.id.cbAuto)
        val cbModel1 = findViewById<CheckBox>(R.id.cbModel1)
        val cbModel2 = findViewById<CheckBox>(R.id.cbModel2)
        val cbModel3 = findViewById<CheckBox>(R.id.cbModel3)
        val cbModel4 = findViewById<CheckBox>(R.id.cbModel4)
        val cbModel5 = findViewById<CheckBox>(R.id.cbModel5)
        val boxes = listOf(cbModel1, cbModel2, cbModel3, cbModel4, cbModel5)

        tvSpeed = findViewById(R.id.tvSpeed)
        tvNow = findViewById(R.id.tvNow)
        tvHistory = findViewById(R.id.tvHistory)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnClear = findViewById<Button>(R.id.btnClearHistory)

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        etKey1.setText(prefs.getString("key1", ""))
        etKey2.setText(prefs.getString("key2", ""))
        etKey3.setText(prefs.getString("key3", ""))
        etKey4.setText(prefs.getString("key4", ""))
        etKey5.setText(prefs.getString("key5", ""))

        cbModel1.isChecked = prefs.getBoolean("model1", true)
        cbModel2.isChecked = prefs.getBoolean("model2", true)
        cbModel3.isChecked = prefs.getBoolean("model3", true)
        cbModel4.isChecked = prefs.getBoolean("model4", false)
        cbModel5.isChecked = prefs.getBoolean("model5", false)

        cbAuto.isChecked = prefs.getBoolean("autoModel", true)
        applyAuto(cbAuto.isChecked, boxes)
        cbAuto.setOnCheckedChangeListener { _, checked -> applyAuto(checked, boxes) }

        refreshLive()

        btnClear.setOnClickListener {
            prefs.edit().remove("switchLog").apply()
            lastLog = null
            refreshLive()
            Toast.makeText(this, getString(R.string.msg_history_cleared), Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("key1", etKey1.text.toString().trim())
                putString("key2", etKey2.text.toString().trim())
                putString("key3", etKey3.text.toString().trim())
                putString("key4", etKey4.text.toString().trim())
                putString("key5", etKey5.text.toString().trim())

                putBoolean("autoModel", cbAuto.isChecked)
                putBoolean("model1", cbModel1.isChecked)
                putBoolean("model2", cbModel2.isChecked)
                putBoolean("model3", cbModel3.isChecked)
                putBoolean("model4", cbModel4.isChecked)
                putBoolean("model5", cbModel5.isChecked)

                apply()
            }
            Toast.makeText(this, getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
            finish()
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

    private fun refreshLive() {
        val p = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        tvSpeed?.text = speedText()
        val key = p.getString("currentKey", "") ?: ""
        val model = p.getString("currentModel", "") ?: ""
        if (key.isBlank() || key == "None" || model.isBlank()) {
            tvNow?.text = getString(R.string.history_now_none)
        } else {
            tvNow?.text = getString(R.string.history_now) + " " + key + " · " + model
        }
        val log = p.getString("switchLog", "") ?: ""
        if (log != lastLog) {
            lastLog = log
            tvHistory?.text = if (log.isBlank()) getString(R.string.history_empty) else log
        }
    }

    private fun applyAuto(auto: Boolean, boxes: List<CheckBox>) {
        for (b in boxes) {
            b.isEnabled = !auto
            b.alpha = if (auto) 0.4f else 1f
        }
    }

    private fun speedText(): String {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val nl = Char(10).toString()
        val known = ArrayList<Pair<String, Float>>()
        val unknown = ArrayList<String>()
        for (m in modelNames) {
            val v = prefs.getFloat("spd_" + m, -1f)
            if (v > 0f) known.add(Pair(m, v)) else unknown.add(m)
        }
        known.sortBy { it.second }
        val sb = StringBuilder()
        sb.append(getString(R.string.speed_title))
        var rank = 1
        for (p in known) {
            val sec = p.second * 14f / 1000f
            sb.append(nl).append(rank).append(". ").append(p.first).append(" — ")
            if (sec > 60f) {
                sb.append(getString(R.string.speed_bad))
            } else {
                sb.append(String.format(Locale.US, "%.1f", sec)).append(" ").append(getString(R.string.speed_sec))
            }
            rank++
        }
        for (m in unknown) {
            sb.append(nl).append("• ").append(m).append(" — ").append(getString(R.string.speed_none))
        }
        return sb.toString()
    }
}
