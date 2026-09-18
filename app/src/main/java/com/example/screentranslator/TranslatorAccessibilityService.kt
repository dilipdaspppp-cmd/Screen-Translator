package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OverlayView : View {
    data class Entry(val text: String, val orig: String, val rect: Rect)

    private var entries: List<Entry> = emptyList()
    private val bgPaint = Paint().apply { color = Color.argb(235, 20, 20, 20) }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private var offX = 0
    private var offY = 0
    private var resolved = false

    constructor(context: Context) : super(context)

    fun setEntries(list: List<Entry>) {
        entries = list
        resolved = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!resolved) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            offX = loc[0]
            offY = loc[1]
            resolved = true
        }
        val cap = 20f * resources.displayMetrics.density
        for (en in entries) {
            val l = en.rect.left.toFloat() - offX - 2f
            val t = en.rect.top.toFloat() - offY - 2f
            val r = en.rect.right.toFloat() - offX + 2f
            val b = en.rect.bottom.toFloat() - offY + 2f
            val w = r - l
            val h = b - t
            if (w <= 0 || h <= 0) continue

            canvas.drawRect(l, t, r, b, bgPaint)

            val usableW = w - 8f
            textPaint.textSize = cap
            val linesOrig = wrap(en.orig, textPaint, usableW).size
            val est = h / (linesOrig * 1.25f)
            var size = if (cap < est) cap else est
            if (size < 10f) size = 10f
            textPaint.textSize = size

            var lines = wrap(en.text, textPaint, usableW)
            while (lines.size * textPaint.textSize * 1.25f > h && textPaint.textSize > 9f) {
                textPaint.textSize = textPaint.textSize - 2f
                lines = wrap(en.text, textPaint, usableW)
            }

            val totalH = lines.size * textPaint.textSize * 1.25f
            var y = t + (h - totalH) / 2f + textPaint.textSize
            for (line in lines) {
                canvas.drawText(line, l + 4f, y, textPaint)
                y += textPaint.textSize * 1.25f
            }
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 0) return listOf(text)
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
}

class TranslatorAccessibilityService : AccessibilityService() {
    private var overlayView: OverlayView? = null
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

    private var exitReceiver: BroadcastReceiver? = null

    private val longPressRunnable = Runnable {
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("uiOff", true).apply()
        syncUiState()
        Toast.makeText(this, "বাটন লুকানো হয়েছে। চালু করতে মেইন অ্যাপে যান", Toast.LENGTH_SHORT).show()
    }

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
        setupOverlay()
        setupFloatingButton()
        uiReady = true
        syncUiState()

        exitReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.screentranslator.EXIT") {
                    shutdown()
                }
            }
        }
        val filter = IntentFilter("com.example.screentranslator.EXIT")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            hideOverlayIfShowing()
            syncUiState()
        }
    }

    override fun onInterrupt() { }

    private fun hideOverlayIfShowing() {
        if (isOverlayShowing) {
            overlayView?.visibility = View.INVISIBLE
            isOverlayShowing = false
            setButtonColor("#2196F3")
        }
    }

    private fun syncUiState() {
        if (!uiReady) return
        val off = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getBoolean("uiOff", false)
        if (off) {
            buttonView?.visibility = View.GONE
            overlayView?.visibility = View.INVISIBLE
            isOverlayShowing = false
        } else {
            buttonView?.visibility = View.VISIBLE
        }
    }

    private fun setupOverlay() {
        overlayView = OverlayView(this).apply {
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
        windowManager?.addView(overlayView, params)
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
                        handler.postDelayed(longPressRunnable, 900)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!moved && (Math.abs(dx) > 30 || Math.abs(dy) > 30)) {
                            moved = true
                            handler.removeCallbacks(longPressRunnable)
                        }
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
                        handler.removeCallbacks(longPressRunnable)
                        val dt = System.currentTimeMillis() - downTime
                        if (!moved && dt < 500) {
                            onButtonTap()
                        }
                        return true
                    }
                }
                return true
            }
        })
    }

    private fun onButtonTap() {
        if (isWorking) return
        if (isOverlayShowing) {
            overlayView?.visibility = View.INVISIBLE
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
        overlayView?.visibility = View.INVISIBLE
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

        val raw = ArrayList<Item>()
        walk(root, raw)
        val items = filterParents(raw)

        if (items.isEmpty()) {
            resetWork()
            Toast.makeText(this, "কোনো লেখা পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        buttonView?.visibility = View.VISIBLE
        Thread { callWithRotation(items) }.start()
    }

    private fun filterParents(raw: List<Item>): List<Item> {
        val result = ArrayList<Item>()
        for (a in raw) {
            var isParent = false
            val aFlat = a.text.replace(" ", "")
            for (b in raw) {
                if (a === b) continue
                if (a.rect.width() >= b.rect.width() && a.rect.height() > b.rect.height()
                    && a.rect.contains(b.rect)
                    && aFlat.contains(b.text.replace(" ", ""))
                    && a.text.length > b.text.length
                ) {
                    isParent = true
                    break
                }
            }
            if (!isParent) result.add(a)
        }
        return result
    }

    private fun walk(node: AccessibilityNodeInfo, items: ArrayList<Item>) {
        if (items.size >= 40) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank() && t.length > 1) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                var clean = t.replace(CHAR_NL, ' ')
                if (clean.length > 700) clean = clean.substring(0, 700)
                items.add(Item(clean, r))
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
                showOverlay(finalText, items)
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
        sb.append("তুমি একজন দক্ষ অনুবাদক। নিচে বিভিন্ন ভাষার লেখা দেওয়া আছে (ইংরেজি, চাইনিজ, উইঘুর বা অন্য যেকোনো ভাষা হতে পারে)। ")
        sb.append("প্রতিটি লাইনকে অত্যন্ত সাবলীল, প্রাঞ্জল ও স্বাভাবিক বাংলায় অনুবাদ করো, যেন মনে হয় বাংলাতেই লেখা হয়েছিল। ")
        sb.append("অর্থ যেন বিকৃত না হয়। লাইনে শুধু সংখ্যা বা কোড থাকলে সেটি হুবহু রেখে দিও। আগে থেকে বাংলায় থাকলে হুবহু রেখে দিও। ")
        sb.append("উত্তর হবে valid JSON: translations নামে array, প্রতিটি entry তে id এবং translated_text। ")
        sb.append("একটি id-ও বাদ দেবে না, ক্রম একই থাকবে। লাইনগুলো:")
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

    private fun showOverlay(responseText: String, items: List<Item>) {
        try {
            val json = JSONObject(responseText)
            val translations = json.getJSONArray("translations")

            val byId = HashMap<Int, String>()
            for (i in 0 until translations.length()) {
                val entry = translations.opt(i)
                if (entry is JSONObject) {
                    val id = entry.optInt("id", i + 1)
                    val tr = entry.optString("translated_text", "")
                    if (tr.isNotBlank()) byId[id] = tr
                }
            }

            val entries = ArrayList<OverlayView.Entry>()
            for (i in items.indices) {
                var translated = byId[i + 1]
                if (translated == null && i < translations.length()) {
                    val entry = translations.opt(i)
                    if (entry is JSONObject) {
                        translated = entry.optString("translated_text", "")
                    } else if (entry != null) {
                        translated = entry.toString()
                    }
                }
                if (translated.isNullOrBlank()) continue
                if (translated == items[i].text) continue
                entries.add(OverlayView.Entry(translated, items[i].text, items[i].rect))
            }

            overlayView?.setEntries(entries)
            overlayView?.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    private fun shutdown() {
        uiReady = false
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        try {
            buttonView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        try {
            overlayView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        buttonView = null
        overlayView = null
        try {
            exitReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        exitReceiver = null
        disableSelf()
    }

    override fun onDestroy() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        try {
            buttonView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        try {
            overlayView?.let { wm.removeView(it) }
        } catch (e: Exception) { }
        try {
            exitReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private val CHAR_NL = Char(10)
    }
}
