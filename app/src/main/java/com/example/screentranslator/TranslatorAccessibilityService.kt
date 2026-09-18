package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TranslatorAccessibilityService : AccessibilityService() {
    private var overlayImage: ImageView? = null
    private var buttonView: TextView? = null
    private var buttonBg: GradientDrawable? = null
    private var buttonParams: android.view.WindowManager.LayoutParams? = null
    private var windowManager: android.view.WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentKeyIndex = 0
    private var currentModelIndex = 0
    private var screenWidth = 1
    private var screenHeight = 1
    private var isWorking = false
    private var isOverlayShowing = false
    private var buttonSize = 1
    private var uiReady = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        setupOverlayImage()
        setupFloatingButton()
        uiReady = true
        syncUiState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            syncUiState()
        }
    }

    override fun onInterrupt() { }

    private fun syncUiState() {
        if (!uiReady) return
        val off = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getBoolean("uiOff", false)
        if (off) {
            buttonView?.visibility = View.GONE
            overlayImage?.visibility = View.INVISIBLE
            isOverlayShowing = false
        } else {
            buttonView?.visibility = View.VISIBLE
        }
    }

    private fun setupFloatingButton() {
        buttonBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2196F3"))
        }
        buttonView = TextView(this).apply {
            text = "অ"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = buttonBg
        }

        buttonSize = dpToPx(44)
        buttonParams = android.view.WindowManager.LayoutParams(
            buttonSize, buttonSize,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        buttonParams?.gravity = Gravity.TOP or Gravity.START
        buttonParams?.x = dpToPx(8)
        buttonParams?.y = screenHeight / 2

        attachButtonTouch()
        windowManager?.addView(buttonView, buttonParams)
    }

    private fun attachButtonTouch() {
        buttonView?.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0f
            private var startY = 0f
            private var downTime = 0L
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = (buttonParams?.x ?: 0).toFloat()
                        startY = (buttonParams?.y ?: 0).toFloat()
                        downTime = System.currentTimeMillis()
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) moved = true
                        if (moved) {
                            var nx = (startX + dx).toInt()
                            var ny = (startY + dy).toInt()
                            if (nx < 0) nx = 0
                            if (ny < 0) ny = 0
                            if (nx > screenWidth - buttonSize) nx = screenWidth - buttonSize
                            if (ny > screenHeight - buttonSize) ny = screenHeight - buttonSize
                            buttonParams?.x = nx
                            buttonParams?.y = ny
                            try {
                                windowManager?.updateViewLayout(v, buttonParams)
                            } catch (e: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt = System.currentTimeMillis() - downTime
                        if (!moved && dt < 500) {
                            onButtonTap()
                        } else if (!moved && dt >= 500) {
                            getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                                .putBoolean("uiOff", true).apply()
                            syncUiState()
                            Toast.makeText(this@TranslatorAccessibilityService, "বন্ধ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun setupOverlayImage() {
        overlayImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.INVISIBLE
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager?.addView(overlayImage, params)
    }

    private fun onButtonTap() {
        if (isWorking) return
        if (isOverlayShowing) {
            overlayImage?.visibility = View.INVISIBLE
            isOverlayShowing = false
            setButtonColor("#2196F3")
        } else {
            translateNow()
        }
    }

    private fun translateNow() {
        isWorking = true
        setButtonColor("#FF9800")
        Toast.makeText(this, "অনুবাদ হচ্ছে...", Toast.LENGTH_SHORT).show()
        buttonView?.visibility = View.GONE
        overlayImage?.visibility = View.INVISIBLE
        handler.postDelayed({ collectAndTranslate() }, 250)
    }

    private data class Item(val text: String, val rect: Rect)

    private fun collectAndTranslate() {
        val root = rootInActiveWindow
        if (root == null) {
            resetWork()
            Toast.makeText(this, "স্ক্রিন পড়া যায়নি, আবার চাপ দিন", Toast.LENGTH_SHORT).show()
            return
        }
        if (root.packageName == packageName) {
            resetWork()
            Toast.makeText(this, "অন্য অ্যাপে গিয়ে বাটনে চাপ দিন", Toast.LENGTH_LONG).show()
            return
        }

        val items = ArrayList<Item>()
        walk(root, items)

        if (items.isEmpty()) {
            resetWork()
            Toast.makeText(this, "কোনো লেখা পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        buttonView?.visibility = View.VISIBLE
        Thread { callWithRotation(items) }.start()
    }

    private fun walk(node: AccessibilityNodeInfo, items: ArrayList<Item>) {
        if (items.size >= 40) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank() && t.length > 1) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                items.add(Item(t.replace(CHAR_NL, ' '), r))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                walk(child, items)
                child.recycle()
            }
        }
    }

    private fun callWithRotation(items: List<Item>) {
        val keys = getKeys()
        val models = getModels()

        if (keys.isEmpty() || models.isEmpty()) {
            handler.post {
                resetWork()
                saveError("সেটিংসে key বা model নেই")
                Toast.makeText(this, "সেটিংসে key বা model নেই", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val prompt = buildPrompt(items)
        val totalCombos = keys.size * models.size
        var attempts = 0
        var resultText: String? = null
        var lastCode = 0

        while (attempts < totalCombos && resultText == null) {
            val key = keys[currentKeyIndex]
            val model = models[currentModelIndex]
            saveCurrentCombo(key, model)
            val result = callGeminiText(key, model, prompt)
            if (result.first != null) {
                resultText = result.first
            } else {
                lastCode = result.second
                moveToNextCombo(keys.size, models.size)
                attempts++
                Thread.sleep(300)
            }
        }

        if (resultText != null) {
            val finalText = resultText
            handler.post {
                drawAndShowOverlay(finalText, items)
                isOverlayShowing = true
                isWorking = false
                setButtonColor("#4CAF50")
                saveError("")
                Toast.makeText(this, "অনুবাদ সম্পন্ন", Toast.LENGTH_SHORT).show()
            }
        } else {
            saveError("সর্বশেষ এরর: HTTP " + lastCode)
            handler.post {
                resetWork()
                Toast.makeText(this, "সব লিমিট শেষ, ১ মিনিট পর আবার চাপ দিন", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildPrompt(items: List<Item>): String {
        val nl = CHAR_NL.toString()
        val sb = StringBuilder()
        sb.append("নিচের প্রতিটি লাইন আলাদা লেখা। সব লাইন বাংলায় অনুবাদ করো। ")
        sb.append("লেখা আগে থেকে বাংলায় থাকলে হুবহু রেখে দিও। ")
        sb.append("উত্তর হবে valid JSON: translations নামে array, ক্রম একই থাকবে, প্রতিটি entry তে translated_text। ")
        sb.append("লাইনগুলো:")
        sb.append(nl)
        for (i in items.indices) {
            sb.append(i + 1).append(". ").append(items[i].text).append(nl)
        }
        sb.append("এখন শুধু JSON দাও।")
        return sb.toString()
    }

    private fun callGeminiText(key: String, model: String, prompt: String): Pair<String?, Int> {
        try {
            val json = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + key
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string()
            response.close()

            if (code == 200 && body != null) {
                val jsonResponse = JSONObject(body)
                val candidates = jsonResponse.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    if (parts.length() > 0) {
                        var text = parts.getJSONObject(0).getString("text")
                        val s = text.indexOf('{')
                        val e = text.lastIndexOf('}')
                        if (s >= 0 && e > s) {
                            text = text.substring(s, e + 1)
                        }
                        return Pair(text, 200)
                    }
                }
            }
            return Pair(null, code)
        } catch (e: Exception) {
            return Pair(null, -1)
        }
    }

    private fun drawAndShowOverlay(responseText: String, items: List<Item>) {
        try {
            val json = JSONObject(responseText)
            val translations = json.getJSONArray("translations")

            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            for (i in 0 until items.size) {
                var translated = ""
                if (i < translations.length()) {
                    val entry = translations.opt(i)
                    if (entry is JSONObject) {
                        translated = entry.optString("translated_text", "")
                    } else if (entry != null) {
                        translated = entry.toString()
                    }
                }
                if (translated.isBlank()) continue

                val r = items[i].rect
                val left = r.left.toFloat()
                val top = r.top.toFloat()
                val right = r.right.toFloat()
                val bottom = r.bottom.toFloat()
                val boxWidth = right - left
                val boxHeight = bottom - top

                val bgPaint = Paint().apply { color = Color.argb(230, 25, 25, 25) }
                canvas.drawRect(left, top, right, bottom, bgPaint)

                val paint = Paint().apply {
                    color = Color.WHITE
                    isAntiAlias = true
                    textSize = boxHeight * 0.6f
                }
                var lines = wrapText(translated, paint, boxWidth)
                while (lines.size * paint.textSize * 1.2f > boxHeight && paint.textSize > 9f) {
                    paint.textSize = paint.textSize - 2f
                    lines = wrapText(translated, paint, boxWidth)
                }

                var y = top + paint.textSize * 1.1f
                for (line in lines) {
                    canvas.drawText(line, left + 4f, y, paint)
                    y += paint.textSize * 1.2f
                }
            }

            overlayImage?.setImageBitmap(bitmap)
            overlayImage?.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(' ')
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (w in words) {
            val test = if (current.isEmpty()) w else current.toString() + " " + w
            if (paint.measureText(test) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current.clear()
                current.append(w)
            } else {
                current.clear()
                current.append(test)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        if (lines.isEmpty()) lines.add(text)
        return lines
    }

    private fun resetWork() {
        isWorking = false
        buttonView?.visibility = View.VISIBLE
        setButtonColor("#2196F3")
    }

    private fun saveError(msg: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putString("lastError", msg).apply()
    }

    private fun setButtonColor(hex: String) {
        buttonBg?.setColor(Color.parseColor(hex))
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getKeys(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return listOf(
            prefs.getString("key1", "") ?: "",
            prefs.getString("key2", "") ?: "",
            prefs.getString("key3", "") ?: "",
            prefs.getString("key4", "") ?: "",
            prefs.getString("key5", "") ?: ""
        ).filter { it.isNotBlank() }
    }

    private fun getModels(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val models = mutableListOf<String>()
        if (prefs.getBoolean("model1", true)) models.add("gemini-3.5-flash-lite")
        if (prefs.getBoolean("model2", true)) models.add("gemini-3.1-flash-lite")
        if (prefs.getBoolean("model3", true)) models.add("gemini-3-flash-preview")
        return models
    }

    private fun saveCurrentCombo(key: String, model: String) {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit().apply {
            putString("currentKey", key.take(8) + "...")
            putString("currentModel", model)
            apply()
        }
    }

    private fun moveToNextCombo(keyCount: Int, modelCount: Int) {
        currentModelIndex++
        if (currentModelIndex >= modelCount) {
            currentModelIndex = 0
            currentKeyIndex = (currentKeyIndex + 1) % keyCount
        }
    }

    override fun onDestroy() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        try {
            buttonView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        try {
            overlayImage?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private val CHAR_NL = Char(10)
    }
}
