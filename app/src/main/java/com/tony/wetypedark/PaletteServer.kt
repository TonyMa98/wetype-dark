package com.tony.wetypedark

import android.content.Context
import com.tony.wetypedark.ui.FkLog
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 手机端调色服务：把原本靠 Python + adb 的桥，改成手机自己跑 HTTP。
 *
 * 路由和电脑版 serve.py 保持一致，页面一行都不用改：
 *   GET  /                页面本体（assets/live-colors/index.html）
 *   GET  /icons/…         页面图标
 *   GET  /ping            页面用它判断「连上了没」
 *   GET  /palette         导出 JSON 时要用的 mic_icon
 *   GET  /read-config     把设备上生效的配色 + 面板参数读回来
 *   GET  /log             旧页面的调试信标，回 204
 *   POST /apply           下发面板参数与整份配色
 *
 * 写输入法私有目录要 root：本模块本来就只在已 root 的 LSP 环境跑，
 * 所以直接 `su -c cp/chown/chmod`，权限组按输入法的真实 uid 动态取。
 */
class PaletteServer(private val ctx: Context, private val port: Int = PORT) {

    companion object {
        const val PORT = 8788

        const val WETYPE_PKG = "com.tencent.wetype"
        const val ACTION_RELOAD = "com.tony.wetypedark.action.PALETTE_RELOAD"
        const val LIVE_FILE = "/data/user/0/$WETYPE_PKG/files/wetype-dark.json"
        const val PALETTE_INNER = "/data/user/0/$WETYPE_PKG/files/appearance-live.json"
        const val PALETTE_REAL = "/data/media/0/Android/data/$WETYPE_PKG/files/appearance-live.json"
        const val PALETTE_SD = "/sdcard/Android/data/$WETYPE_PKG/files/appearance-live.json"
    }

    private fun Lg(m: String) = Log.w("PaletteSrv", m)

    private val pool = Executors.newCachedThreadPool()
    private val writeLock = Any()
    @Volatile private var lastSig: String? = null

    @Volatile var running = false
        private set
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            serverSocket = ss
            running = true
            acceptThread = Thread({
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    Lg("srv accept ${client.remoteSocketAddress}")
                    try {
                        pool.execute { handle(client) }
                    } catch (t: Throwable) {
                        // 线程池拒绝 / OOM 之类不能把 accept 线程带走，否则端口还开着
                        // 但再也没人 accept，表现就是页面请求全部超时。
                        Lg("dispatch failed: ${t.javaClass.simpleName} ${t.message}")
                        runCatching { client.close() }
                    }
                }
            }, "palette-http").also { it.isDaemon = true; it.start() }
            true
        } catch (e: Exception) {
            running = false
            Lg("端口 $port 起不来: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val out = BufferedOutputStream(client.getOutputStream())
            val head = readHead(input) ?: run { Lg("srv head null"); return }
            Lg("srv ${head.method} ${head.target}")
            val method = head.method
            val path = head.target.substringBefore('?')

            when {
                method == "POST" && path == "/apply" -> {
                    val body = readBody(input, head.contentLength)
                    Lg("apply len=${head.contentLength} got=${body.length}")
                    val res = apply(body)
                    write(out, res.first, res.second)
                }

                method == "GET" && (path == "/" || path == "/index.html") ->
                    write(out, 200, asset("live-colors/index.html") ?: ByteArray(0), "text/html; charset=utf-8")

                method == "GET" && path.startsWith("/icons/") -> {
                    val rel = path.removePrefix("/icons/")
                    if (rel.contains("..")) {
                        write(out, 404, json("{\"ok\":false,\"error\":\"not found\"}"))
                    } else {
                        val bytes = asset("live-colors/icons/$rel")
                        if (bytes == null) write(out, 404, json("{\"ok\":false}"))
                        else write(
                            out, 200, bytes,
                            if (rel.endsWith(".svg")) "image/svg+xml" else "application/octet-stream",
                        )
                    }
                }

                method == "GET" && path == "/ping" ->
                    write(out, 200, json("{\"ok\":true,\"serial\":\"phone\",\"time\":${System.currentTimeMillis()}}"))

                method == "GET" && path == "/palette" -> {
                    val mic = runCatching { JSONObject(readDevice(PALETTE_REAL)).optString("mic_icon") }
                        .getOrDefault("")
                    write(out, 200, json("{\"ok\":true,\"mic_icon\":${JSONObject.quote(mic)}}"))
                }

                method == "GET" && path == "/read-config" -> {
                    val paletteText = listOf(PALETTE_INNER, PALETTE_REAL, PALETTE_SD)
                        .firstNotNullOfOrNull { p -> readDevice(p)?.takeIf { it.trim().startsWith("{") } }
                    if (paletteText == null) {
                        write(out, 404, json("{\"ok\":false,\"error\":\"手机上还没有 appearance-live.json，先在页面调一次色再读\"}"))
                    } else {
                        val liveText = runCatching { readDevice(LIVE_FILE) }
                            .getOrNull()?.takeIf { it.trim().startsWith("{") } ?: "{}"
                        val body = "{\"ok\":true,\"serial\":\"phone\",\"palette\":$paletteText,\"live\":$liveText}"
                        write(out, 200, json(body))
                    }
                }

                method == "GET" && path == "/log" -> write(out, 204, ByteArray(0))

                else -> write(out, 404, json("{\"ok\":false,\"error\":\"not found\"}"))
            }
            out.flush()
        } catch (e: Exception) {
            Lg("请求处理失败: ${e.javaClass.simpleName}")
        } finally {
            runCatching { client.close() }
        }
    }

    private data class ReqHead(val method: String, val target: String, val contentLength: Int)

    private fun readHead(input: java.io.InputStream): ReqHead? {
        // 必须读到完整的 \r\n\r\n 才算到底，只遇到第一个换行会漏掉后面的头
        val raw = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            raw.append(b.toChar())
            if (raw.length >= 4 && raw.endsWith("\r\n\r\n")) break
            if (raw.length > 16384) return null
        }
        val lines = raw.toString().split("\r\n")
        val first = lines.firstOrNull()?.trim().orEmpty().split(' ')
        if (first.size < 2) return null
        val len = lines.asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        return ReqHead(first[0], first[1], len)
    }

    private fun readBody(input: java.io.InputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        return runCatching {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun write(out: BufferedOutputStream, code: Int, body: ByteArray, type: String = "application/json; charset=utf-8") {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray()
        out.write(head)
        out.write(body)
    }

    private fun json(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    private fun asset(path: String): ByteArray? = runCatching {
        ctx.assets.open(path).use { it.readBytes() }
    }.getOrNull()

    // ------------------------------------------------------------------ 下发

    private fun apply(raw: String): Pair<Int, ByteArray> {
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            return 400 to json("{\"ok\":false,\"error\":\"bad json\"}")
        }

        val panel = payload.optString("panelColor").trim()
        if (!panel.startsWith("#") || panel.length !in listOf(7, 9)) {
            return 400 to json("{\"ok\":false,\"error\":\"panelColor 需要 #RRGGBB 或 #AARRGGBB\"}")
        }
        val blur = payload.optInt("blurRadiusDp", 24).coerceIn(0, 60)

        val clean = JSONObject().apply {
            put("panelColor", panel.uppercase())
            put("blurRadiusDp", blur)
            for (k in listOf("logoIconColor", "logoCircleColor", "fnIconColor", "accentColor")) {
                val v = payload.optString(k).trim()
                if (v.isEmpty()) continue
                if (!v.startsWith("#") || v.length !in listOf(7, 9)) {
                    return 400 to json("{\"ok\":false,\"error\":\"$k 需要 #RRGGBB 或 #AARRGGBB\"}")
                }
                put(k, v.uppercase())
            }
        }

        val palette = payload.optJSONObject("palette")
        val sig = clean.toString() + "|" + (palette?.toString() ?: "")
        synchronized(writeLock) {
            if (sig == lastSig) {
                return 200 to json("{\"ok\":true,\"serial\":\"phone\",\"skipped\":true}")
            }
            // 关键点：su 读不到 App 私有缓存（SELinux 会回 ENOENT），
            // 以前把内容先写进 cacheDir 再让 su 去 cp，写入永远是卡住的。
            // 现在直接把内容喂给 su 里的 cat，一次进程写一个文件。
            val liveCmd = "cat > $LIVE_FILE.wtmp && chmod 644 $LIVE_FILE.wtmp && mv $LIVE_FILE.wtmp $LIVE_FILE"
            val (liveCode, liveOut) = suWrite(liveCmd, clean.toString())
            if (liveCode != 0) {
                Lg("apply live rc=$liveCode ${liveOut.take(300)}")
                return 500 to json("{\"ok\":false,\"error\":\"写入失败，看看 root 授权\"}")
            }
            if (palette != null) {
                val uid = wetypeUid()
                val palCmd = buildString {
                    append("cat > $PALETTE_REAL.wtmp")
                    append(" && chown $uid:ext_data_rw $PALETTE_REAL.wtmp")
                    append(" && chmod 666 $PALETTE_REAL.wtmp")
                    append(" && mv $PALETTE_REAL.wtmp $PALETTE_REAL")
                    append(" && mkdir -p /data/user/0/$WETYPE_PKG/files")
                    append(" && cp -f $PALETTE_REAL $PALETTE_INNER.wtmp && chown $uid:$uid $PALETTE_INNER.wtmp")
                    append(" && chmod 644 $PALETTE_INNER.wtmp && mv $PALETTE_INNER.wtmp $PALETTE_INNER")
                }
                val (palCode, palOut) = suWrite(palCmd, palette.toString())
                if (palCode != 0) {
                    Lg("apply palette rc=$palCode ${palOut.take(80)}")
                    return 500 to json("{\"ok\":false,\"error\":\"写入失败，看看 root 授权\"}")
                }
            }
            lastSig = sig
        }
        pokeKeyboard()
        return 200 to json("{\"ok\":true,\"serial\":\"phone\",\"payload\":$clean,\"palette\":${palette != null}}")
    }

    /**
     * 把 text 通过 stdin 交给 su 里的脚本（脚本里用 cat 读）。
     * 这样不用在 App 私有目录落临时文件——su 那边的 SELinux 上下文读不到 cacheDir。
     * 同时带上超时，避免 su 卡住把整个服务线程堵死。
     */
    private fun suWrite(script: String, text: String): Pair<Int, String> {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-M", "-c", script))
            val sb = StringBuilder()
            val t1 = Thread { runCatching { p.inputStream.bufferedReader().use { r -> sb.append(r.readText()) } } }
            val t2 = Thread { runCatching { p.errorStream.bufferedReader().use { r -> sb.append(r.readText()) } } }
            t1.start(); t2.start()
            runCatching { p.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
            val done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                runCatching { p.destroy() }
                return@runCatching -1 to "timeout"
            }
            t1.join(1000); t2.join(1000)
            p.exitValue() to sb.toString()
        }.getOrDefault(-1 to "")
    }

    /**
     * 写盘之后捅一下键盘进程：模块在微信输入法里注册了同名校验广播，
     * 收到就立刻重读配置并重画。没有这一步时，键盘开着也要等收起再唤起才变。
     */
    private fun pokeKeyboard() {
        runCatching {
            val intent = android.content.Intent(ACTION_RELOAD).setPackage(WETYPE_PKG)
            ctx.sendBroadcast(intent)
        }
    }

    private fun wetypeUid(): Int = runCatching {
        sh("stat -c %u /data/user/0/$WETYPE_PKG").trim().toIntOrNull() ?: 0
    }.getOrDefault(0)

    private fun readDevice(path: String): String? = runCatching {
        val out = sh("cat $path")
        out.trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 走 root 执行；su 授权过之后就是静默的，首次会弹一次 Magisk 授权。 */
    private fun sh(script: String): String = shCode(script).second

    private fun shCode(script: String): Pair<Int, String> {
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-M", "-c", script))
            val sb = StringBuilder()
            val t1 = Thread {
                runCatching {
                    p.inputStream.bufferedReader().use { r -> sb.append(r.readText()) }
                }
            }
            val t2 = Thread {
                runCatching {
                    p.errorStream.bufferedReader().use { r -> sb.append(r.readText()) }
                }
            }
            t1.start(); t2.start()
            p.waitFor()
            t1.join(2000); t2.join(2000)
            p.exitValue() to sb.toString()
        }.getOrDefault(-1 to "")
    }
}
