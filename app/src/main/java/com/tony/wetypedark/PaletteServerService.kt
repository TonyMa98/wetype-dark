package com.tony.wetypedark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.tony.wetypedark.ui.AppsActivity
import com.tony.wetypedark.ui.FkLog
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 手机端调色台的前台服务：开关一开就在 8777 上挂着，
 * 电脑同 Wi-Fi 下浏览器输入通知/设置页里那串地址即可。
 *
 * 前台服务只为了让系统别在锁屏后把监听掐掉；屏幕可以关，页面不用留在手机上。
 */
class PaletteServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "palette-server"
        private const val NOTIFY_ID = 1001
        const val ACTION_START = "com.tony.wetypedark.action.PALETTE_START"
        const val ACTION_STOP = "com.tony.wetypedark.action.PALETTE_STOP"

        @Volatile
        var running = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, PaletteServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PaletteServerService::class.java))
        }

        /** 局域网 IPv4，优先 wlan 网卡；拿不到就返回 null。 */
        fun lanIp(): String? {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
            }.getOrNull() ?: runCatching {
                NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
            }.getOrNull()
        }

        fun url(): String = "http://${lanIp() ?: "手机IP"}:${WebRelay.PUBLIC_PORT}"
    }

    private var server: PaletteServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, buildNotification())
        if (server == null) {
            val s = PaletteServer(this)
            server = s
            running = s.start()
            FkLog.w("调色服务启动: server=$running ${url()}")
            // 转发要提权，首次会弹 root 授权；放到后台线程等，别卡住主线程
            Thread {
                val relayOk = WebRelay.start()
                FkLog.w("调色转发: ok=$relayOk err=${WebRelay.lastError}")
            }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        runCatching { WebRelay.stop() }
        runCatching { server?.stop() }
        server = null
        running = false
        runCatching { stopForeground(true) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "手机端调色台",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "在手机上提供调色页面，电脑浏览器直接访问"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AppsActivity::class.java).putExtra(AppsActivity.EXTRA_TAB, 1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PaletteServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("调色台运行中")
            .setContentText(url())
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
    }
}
