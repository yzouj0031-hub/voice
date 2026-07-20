package com.voiceassistant.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
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

    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingUtterances = AtomicInteger(0)

    // 当前聊天请求的编号，用于让旧请求的结果失效（避免串台）
    private val chatSeq = AtomicInteger(0)
    @Volatile private var currentConn: HttpURLConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage 存设置
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(Bridge(), "Native")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.loadUrl("file:///android_asset/index.html")

        initTts()
        ensureMicPermission()
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
    }

    // ---------------- 语音识别 ----------------
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
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    private fun firstResult(bundle: Bundle?): String? {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()
    }

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
        val call = if (arg == null) {
            "window.$func && window.$func();"
        } else {
            "window.$func && window.$func(${JSONObject.quote(arg)});"
        }
        main.post { webView.evaluateJavascript(call, null) }
    }

    override fun onBackPressed() {
        // 让网页有机会先关闭“设置”弹窗
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
