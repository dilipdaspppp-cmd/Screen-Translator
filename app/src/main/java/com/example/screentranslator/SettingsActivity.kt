package com.example.screentranslator

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etKey1 = findViewById<EditText>(R.id.etKey1)
        val etKey2 = findViewById<EditText>(R.id.etKey2)
        val etKey3 = findViewById<EditText>(R.id.etKey3)
        val etKey4 = findViewById<EditText>(R.id.etKey4)
        val etKey5 = findViewById<EditText>(R.id.etKey5)

        val cbModel1 = findViewById<CheckBox>(R.id.cbModel1)
        val cbModel2 = findViewById<CheckBox>(R.id.cbModel2)
        val cbModel3 = findViewById<CheckBox>(R.id.cbModel3)

        val btnSave = findViewById<Button>(R.id.btnSave)

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        etKey1.setText(prefs.getString("key1", ""))
        etKey2.setText(prefs.getString("key2", ""))
        etKey3.setText(prefs.getString("key3", ""))
        etKey4.setText(prefs.getString("key4", ""))
        etKey5.setText(prefs.getString("key5", ""))

        cbModel1.isChecked = prefs.getBoolean("model1", true)
        cbModel2.isChecked = prefs.getBoolean("model2", true)
        cbModel3.isChecked = prefs.getBoolean("model3", true)

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("key1", etKey1.text.toString().trim())
                putString("key2", etKey2.text.toString().trim())
                putString("key3", etKey3.text.toString().trim())
                putString("key4", etKey4.text.toString().trim())
                putString("key5", etKey5.text.toString().trim())

                putBoolean("model1", cbModel1.isChecked)
                putBoolean("model2", cbModel2.isChecked)
                putBoolean("model3", cbModel3.isChecked)

                apply()
            }
            Toast.makeText(this, "সেটিংস সেভ হয়েছে!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
