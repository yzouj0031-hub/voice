package com.voiceassistant.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 离线唤醒词模型（Vosk 中文小模型）的下载与解压管理。
 * 模型约 42MB，只在用户开启"语音唤醒"时下载一次，存到应用私有目录，
 * 这样安装包本身保持小巧。
 */
object WakeModel {
    // 官方地址；备用镜像放 HuggingFace（国内一般也能连）
    private const val URL_MAIN = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    private const val URL_MIRROR =
        "https://huggingface.co/spaces/prithivMLmods/vosk-models/resolve/main/vosk-model-small-cn-0.22.zip"
    private const val DIR = "vosk-model-cn"            // filesDir 下的根目录
    private const val INNER = "vosk-model-small-cn-0.22" // zip 里的顶层文件夹

    fun modelPath(ctx: Context): File = File(File(ctx.filesDir, DIR), INNER)

    fun isReady(ctx: Context): Boolean {
        val p = modelPath(ctx)
        // 解压完成的标志：模型目录里应有 am / conf 等子目录
        return File(p, "am").isDirectory && File(p, "conf").isDirectory
    }

    /**
     * 下载并解压模型。onProgress 传 0~100（下载阶段），解压阶段传 -1。
     * 成功返回 null，失败返回错误信息。
     */
    fun ensure(ctx: Context, onProgress: (Int) -> Unit): String? {
        if (isReady(ctx)) return null
        val root = File(ctx.filesDir, DIR).apply { mkdirs() }
        val zipFile = File(ctx.cacheDir, "vosk-cn.zip")

        var err = downloadTo(URL_MAIN, zipFile, onProgress)
        if (err != null) {
            // 主地址失败时尝试镜像
            err = downloadTo(URL_MIRROR, zipFile, onProgress)
            if (err != null) return "下载唤醒模型失败：$err"
        }

        return try {
            onProgress(-1) // 解压中
            unzip(zipFile, root)
            zipFile.delete()
            if (isReady(ctx)) null else "模型解压后不完整，请重试。"
        } catch (e: Exception) {
            "解压唤醒模型失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun downloadTo(urlStr: String, out: File, onProgress: (Int) -> Unit): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) return "服务器返回 $code"
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        fos.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            conn?.disconnect()
        }
    }

    private fun unzip(zip: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                // 防 zip slip 路径穿越
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                    throw SecurityException("非法的压缩包路径：${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
