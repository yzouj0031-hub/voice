package com.voiceassistant.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 语音助手主界面。
 * 界面用网页（assets 里的 HTML/CSS/JS）实现，语音识别、语音合成、网络请求
 * 都由这里的原生代码完成，通过 window.Native 桥接暴露给网页。
 * 网络请求走原生，彻底绕开 WebView 的跨域限制，兼容任意 AI 接口。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // 供后台唤醒服务判断：App 在前台时，服务不抢麦克风
        @Volatile var appInForeground = false
    }

    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingUtterances = AtomicInteger(0)

    private val chatSeq = AtomicInteger(0)
    @Volatile private var currentConn: HttpURLConnection? = null

    private var pageLoaded = false
    private var pendingWake = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(), "Native")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                if (pendingWake) { pendingWake = false; triggerAutoStart() }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.loadUrl("file:///android_asset/index.html")

        initTts()
        ensureMicPermission()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("wake", false) == true) {
            if (pageLoaded) triggerAutoStart() else pendingWake = true
        }
    }

    // 被唤醒后自动开始一轮对话（稍作延迟，确保唤醒服务已释放麦克风）
    private fun triggerAutoStart() {
        main.postDelayed({ dispatch("autoStartConversation") }, 600)
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
    }

    override fun onStop() {
        appInForeground = false
        super.onStop()
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------- 语音合成 ----------------
    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (pendingUtterances.decrementAndGet() <= 0) dispatch("onTtsDone")
            }
            @Deprecated("deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                if (pendingUtterances.decrementAndGet() <= 0) dispatch("onTtsDone")
            }
        })
    }

    // ---------------- JS 桥接 ----------------
    inner class Bridge {
        @JavascriptInterface
        fun startListening() = main.post { startRecognition() }

        @JavascriptInterface
        fun stopListening() = main.post { recognizer?.stopListening() }

        @JavascriptInterface
        fun speak(text: String) = main.post {
            if (!ttsReady || text.isBlank()) return@post
            pendingUtterances.incrementAndGet()
            dispatch("onTtsStart")
            val id = "u" + System.nanoTime()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        }

        @JavascriptInterface
        fun stopSpeaking() = main.post {
            tts?.stop()
            pendingUtterances.set(0)
            dispatch("onTtsDone")
        }

        @JavascriptInterface
        fun chat(payloadJson: String) {
            val seq = chatSeq.incrementAndGet()
            currentConn?.disconnect()
            net.execute { runChat(payloadJson, seq) }
        }

        // ---- 语音唤醒 ----
        @JavascriptInterface
        fun setWakeEnabled(enabled: Boolean, word: String) = main.post {
            if (enabled) {
                if (!hasMic()) {   // 没有麦克风权限时先申请，避免前台服务因缺权限崩溃
                    ensureMicPermission()
                    dispatch("onSpeechError", "请先允许麦克风权限，再开启语音唤醒。")
                    return@post
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2
                    )
                }
                val i = Intent(this@MainActivity, WakeService::class.java).apply {
                    action = WakeService.ACTION_START
                    putExtra(WakeService.EXTRA_WORD, word)
                }
                ContextCompat.startForegroundService(this@MainActivity, i)
            } else {
                val i = Intent(this@MainActivity, WakeService::class.java).apply {
                    action = WakeService.ACTION_STOP
                }
                ContextCompat.startForegroundService(this@MainActivity, i)
            }
        }

        @JavascriptInterface
        fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this@MainActivity)

        @JavascriptInterface
        fun openOverlaySettings() = main.post {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try { startActivity(i) } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        }
    }

    // ---------------- 语音识别（对话） ----------------
    private fun startRecognition() {
        if (!hasMic()) {
            ensureMicPermission()
            dispatch("onSpeechError", "没有麦克风权限，请在系统设置里允许。")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            dispatch("onSpeechError", "系统没有可用的语音识别服务（需安装/启用 Google 语音）。")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = dispatch("onSpeechStart")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partial: Bundle?) {
                    firstResult(partial)?.let { dispatch("onSpeechPartial", it) }
                }

                override fun onResults(results: Bundle?) {
                    val text = firstResult(results) ?: ""
                    dispatch("onSpeechResult", text)
                    dispatch("onSpeechEnd")
                }

                override fun onError(error: Int) {
                    dispatch("onSpeechEnd")
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，请再点一次麦克风。"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限。"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，请检查网络。"
                        else -> null
                    }
                    if (msg != null) dispatch("onSpeechError", msg)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ---------------- 聊天：原生流式请求 ----------------
    private fun runChat(payloadJson: String, seq: Int) {
        fun alive() = seq == chatSeq.get()
        try {
            val p = JSONObject(payloadJson)
            val provider = p.optString("provider", "openai")
            val baseUrl = p.optString("baseUrl").trimEnd('/')
            val apiKey = p.optString("apiKey")
            val model = p.optString("model")
            val system = p.optString("system")
            val messages = p.optJSONArray("messages") ?: JSONArray()

            if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                if (alive()) dispatch("onChatError", "请先在设置里填写接口地址、密钥和模型。")
                return
            }

            val isAnthropic = provider == "anthropic"
            val url = URL(if (isAnthropic) "$baseUrl/v1/messages" else "$baseUrl/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20000
                readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                if (isAnthropic) {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            currentConn = conn

            val body = if (isAnthropic) {
                JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 1024)
                    put("stream", true)
                    if (system.isNotEmpty()) put("system", system)
                    put("messages", messages)
                }
            } else {
                val msgs = JSONArray()
                if (system.isNotEmpty()) {
                    msgs.put(JSONObject().put("role", "system").put("content", system))
                }
                for (i in 0 until messages.length()) msgs.put(messages.get(i))
                JSONObject().apply {
                    put("model", model)
                    put("stream", true)
                    put("messages", msgs)
                }
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (alive()) dispatch("onChatError", "接口返回 $code：${err.take(300)}")
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!alive()) return
                    val t = line!!.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.substring(5).trim()
                    if (data.isEmpty()) continue
                    if (!isAnthropic && data == "[DONE]") break
                    val delta = if (isAnthropic) parseAnthropic(data) else parseOpenAI(data)
                    if (!delta.isNullOrEmpty()) dispatch("onChatDelta", delta)
                }
            }
            if (alive()) dispatch("onChatDone")
        } catch (e: Exception) {
            if (alive()) dispatch("onChatError", "请求失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            currentConn = null
        }
    }

    private fun parseOpenAI(data: String): String? = try {
        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optString("content")
    } catch (_: Exception) { null }

    private fun parseAnthropic(data: String): String? = try {
        val obj = JSONObject(data)
        if (obj.optString("type") == "content_block_delta") {
            val d = obj.optJSONObject("delta")
            if (d?.optString("type") == "text_delta") d.optString("text") else null
        } else null
    } catch (_: Exception) { null }

    // ---------------- 调用网页里的回调 ----------------
    private fun dispatch(func: String, arg: String? = null) {
        val call = if (arg == null) "window.$func && window.$func();"
        else "window.$func && window.$func(${JSONObject.quote(arg)});"
        main.post { webView.evaluateJavascript(call, null) }
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false") { result ->
            if (result != "true") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        currentConn?.disconnect()
        net.shutdownNow()
        super.onDestroy()
    }
}
