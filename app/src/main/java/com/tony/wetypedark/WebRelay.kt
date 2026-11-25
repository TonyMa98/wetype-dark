package com.tony.wetypedark

import java.io.File

/**
 * ColorOS 会拦掉普通应用监听 socket 的局域网入站（OPPO 的 netd socket filter，
 * 普通 uid 的连接一进来就被 RST）。root 进程的 socket 不受这个限制，
 * 所以对外开一个 root 侧的轻量转发：外部 8777 → 本机回环上的 PaletteServer。
 *
 * 转发器用 busybox nc -lk -e <脚本>：每来一个连接就 exec 一次
 * `toybox nc 127.0.0.1 <内部端口>`，stdin/stdout 直接接到 socket，等于一条透传管道。
 */
object WebRelay {

    const val PUBLIC_PORT = 8777
    private const val SCRIPT = "/data/local/tmp/wetype_web_relay.sh"
    private val BUSYBOX = listOf(
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox",
        "/data/adb/magisk/busybox",
        "/data/adb/magisk/busybox" + "-standalone",
        "/system/xbin/busybox",
        "/system/bin/busybox",
    )

    @Volatile
    var running = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 注意：App 是非 root 进程，/data/adb/ksu 这类目录对它不可见，
     * 所以只能用 su 去探测，不能靠 File.exists()。
     */
    fun busybox(): String? {
        val list = BUSYBOX.joinToString(" ")
        val (_, out) = shCode("for p in $list; do [ -x \"\$p\" ] && echo \"\$p\" && break; done")
        return out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("/") }
    }

    fun start(): Boolean {
        val bb = busybox() ?: run { lastError = "找不到 busybox"; return false }
        stop()
        shCode(
            "echo '#!/system/bin/sh' > $SCRIPT; " +
                "echo 'exec /system/bin/toybox nc 127.0.0.1 ${PaletteServer.PORT}' >> $SCRIPT; " +
                "chmod 755 $SCRIPT"
        )
        val (code, out) = shCode("nohup $bb nc -lk -p $PUBLIC_PORT -e $SCRIPT >/dev/null 2>&1 &")
        // 转发器是后台拉起的，su 立刻返回；真正有没有在监听要等一下再看
        running = code == 0 && awaitListening()
        if (!running) lastError = out.ifBlank { "root 授权失败或转发没起来" }
        return running
    }

    fun stop() {
        val bb = busybox()
        if (bb != null) shCode("$bb pkill -f 'nc -lk -p $PUBLIC_PORT'")
        shCode("rm -f $SCRIPT")
        running = false
    }

    /** 轮询 /proc/net/tcp，确认有进程在 0.0.0.0:PUBLIC_PORT 上 LISTEN。 */
    private fun awaitListening(timeoutMs: Long = 3000): Boolean {
        val hexPort = PUBLIC_PORT.toString(16).uppercase().padStart(4, '0')
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                val ok = File("/proc/net/tcp").readLines().any {
                    val f = it.trim().split(" ")
                    f.size > 3 && f[1].endsWith(":$hexPort") && f[3] == "0A"
                }
                if (ok) return true
            }
            Thread.sleep(150)
        }
        return false
    }

    private fun shCode(script: String): Pair<Int, String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val sb = StringBuilder()
        val t1 = Thread { runCatching { p.inputStream.bufferedReader().use { sb.append(it.readText()) } } }
        val t2 = Thread { runCatching { p.errorStream.bufferedReader().use { sb.append(it.readText()) } } }
        t1.start(); t2.start()
        p.waitFor()
        t1.join(2000); t2.join(2000)
        p.exitValue() to sb.toString()
    }.getOrDefault(-1 to "")
}
