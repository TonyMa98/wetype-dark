package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 注入进程内的日志通道。
 *
 * 为什么不用 logcat：
 *  - 实测微信输入法 `:hld` 进程的 `Log.i` 输出极少被 logcat 捕获（可能与它的日志策略/缓冲区有关），
 *    调试期间多轮尝试 `adb logcat -s` 都拿不到模块日志，只能靠写文件。
 *
 * 为什么写到微信输入法自己的目录：
 *  - `/data/user/0/com.tencent.wetype/files/` 对 `:hld` 进程天然可写，不需要 root；
 *  - 取文件时用 `adb shell su -c cat`，避开 scoped storage。
 *
 * 注意：`appendText` 是**追加**写，不会覆盖历史记录。排查时先 `rm` 再触发，避免旧行干扰。
 *
 * 异步批量写（2026-11-04）：
 *  - 原来每行日志都在调用线程上同步 `File.exists()+length()+appendText`（开/写/关文件），
 *    打字时 KeyShape/gesture/KeyTextTuning 高频刷屏，每行一次磁盘同步 I/O，是「打字不跟手」的重要嫌疑。
 *  - 现在改为：调用线程只做 `Log.i` + 把「带时间戳的一行」塞进有界队列（不碰磁盘）；
 *    一个 daemon 线程把队列里的行聚成一批（250ms 窗口或 8KB 上限），一次性 appendText 落盘。
 *  - `flush()` 可强制同步落盘（自测/查日志前调用）。
 */
object FkLog {

    const val TAG = "FkModule"

    /** 微信输入法主数据目录（`:hld` 进程直接可写） */
    private const val DIR = "/data/user/0/com.tencent.wetype/files"

    /** 主日志文件 */
    private const val FILE = "$DIR/fk_log.txt"

    /** 单文件上限，超过则清空重写，避免无限增长 */
    private const val MAX_BYTES = 512 * 1024L

    /** 攒批窗口：首行到达后最多再等这么久，把期间到达的行合并成一次写盘 */
    private const val FLUSH_INTERVAL_MS = 250L

    /** 单批字符上限，防止一次拼太大 */
    private const val MAX_BATCH_CHARS = 8192

    /** 队列积压上限，极端刷屏时丢弃最旧的行，防止内存无界增长 */
    private const val MAX_QUEUE = 4000

    // SimpleDateFormat 非线程安全；d() 可能被多线程调用，用 ThreadLocal 隔离
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    @Volatile
    private var enabled = true

    private val queue = LinkedBlockingQueue<String>(MAX_QUEUE)

    /** 后台落盘线程：阻塞取第一行 → 250ms 窗口内聚批 → 一次 appendText */
    private val writer = Thread {
        val sb = StringBuilder()
        while (true) {
            try {
                val first = queue.take() // 无日志时阻塞休眠，不空转
                sb.append(first)
                var chars = first.length
                val deadline = System.currentTimeMillis() + FLUSH_INTERVAL_MS
                while (chars < MAX_BATCH_CHARS) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    val more = queue.poll(remain, TimeUnit.MILLISECONDS) ?: break
                    sb.append(more)
                    chars += more.length
                }
                writeToFile(sb.toString())
                sb.setLength(0)
            } catch (_: InterruptedException) {
                // 进程退出/中断，忽略
            } catch (_: Throwable) {
                // 任何异常都不能让写线程死掉
                sb.setLength(0)
            }
        }
    }.apply {
        isDaemon = true
        name = "FkLogWriter"
        start()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun d(message: String) {
        Log.i(TAG, message)
        if (!enabled) return
        val line = "[${stamp()}] $message\n"
        if (!queue.offer(line)) {
            // 队列满：丢掉最旧的一行再塞入，保证最新状态可见
            queue.poll()
            queue.offer(line)
        }
    }

    /** 记录一条不致命的告警（功能降级、找不到目标等） */
    fun w(message: String) {
        d("WARN $message")
    }

    /**
     * 记录一次异常。带上调用点和方法名，方便定位是哪一层炸的。
     */
    fun e(message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: ""
        d("ERROR $message${if (detail.isNotEmpty()) " | $detail" else ""}")
    }

    /**
     * 强制把队列里已积攒的日志立刻落盘（自测或取日志前调用）。
     * 只刷当前积压，不等待 writer 线程状态同步。
     */
    fun flush() {
        if (queue.isEmpty()) return
        val drain = ArrayList<String>(queue.size)
        queue.drainTo(drain)
        if (drain.isEmpty()) return
        val sb = StringBuilder()
        for (s in drain) sb.append(s)
        writeToFile(sb.toString())
    }

    private fun stamp(): String {
        val fmt = timeFormat.get() ?: SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return synchronized(fmt) { fmt.format(Date()) }
    }

    private fun writeToFile(text: String) {
        runCatching {
            val file = File(FILE)
            if (file.exists() && file.length() > MAX_BYTES) {
                file.delete()
            }
            file.appendText(text)
        }
    }

    /**
     * 每行都带时间戳的批量写入，用于一次性 dump 一段完整信息。
     */
    fun block(message: String) {
        d(message)
    }
}
