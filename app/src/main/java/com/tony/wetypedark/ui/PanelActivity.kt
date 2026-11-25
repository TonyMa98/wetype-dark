package com.tony.wetypedark.ui

import com.tony.wetypedark.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * 两个页面共用的外壳：wetype-dark 顶部栏、底部 COUI 悬浮胶囊、底部渐隐。
 * 复刻自 wedark 的 SettingsActivity（同一套尺寸与配色常量）。
 */
abstract class PanelActivity : Activity() {

    protected var darkTheme = false

    protected val ACCENT: Int get() = getColor(R.color.settings_accent)
    protected val BG: Int get() = getColor(R.color.settings_bg)
    protected val CARD: Int get() = getColor(R.color.settings_card)
    protected val TEXT_MAIN: Int get() = getColor(R.color.settings_text_main)
    protected val TEXT_SUB: Int get() = getColor(R.color.settings_text_sub)
    protected val TEXT_DIM: Int get() = getColor(R.color.settings_text_dim)
    protected val DIVIDER: Int get() = getColor(R.color.settings_divider)

    /** 当前页在底栏里的位置：0 = 应用，1 = 设置 */
    protected abstract val tabIndex: Int

    protected abstract fun buildPage(): View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        darkTheme = !resolveIsLightTheme(this)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            !darkTheme

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            clipChildren = false
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        column.addView(buildHeader())
        val contentFrame = FrameLayout(this)
        column.addView(contentFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        contentFrame.addView(buildPage())

        root.addView(buildBottomScrim(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(150),
            Gravity.BOTTOM
        ))

        val tabBar = buildTabBar()
        val nativeBar = tabBar.javaClass.name.startsWith("com.coui.")
        root.addView(tabBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (nativeBar) FrameLayout.LayoutParams.WRAP_CONTENT else dp(88),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(if (nativeBar) 20 else 8) })

        setContentView(root)
    }

    // ---------------------------------------------------------------- 顶部栏

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun buildHeader(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, statusBarHeight(), 0, 0)
            clipChildren = false
            clipToPadding = false
        }

        val fab = CouiFabButton.build(
            this,
            ContextCompat.getDrawable(this, R.drawable.ic_restart),
            { restartWetype() },
            sizeDp = 40,
        )
        if (fab != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(TextView(this@PanelActivity).apply {
                    text = "wetype-dark"
                    textSize = 26f
                    isSingleLine = true
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    paint.isFakeBoldText = true
                    setTextColor(
                        ContextCompat.getColor(this@PanelActivity, R.color.settings_text_main)
                    )
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(20) })

                addView(fab, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(16)
                })
            }
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40 + 6 + 6)
            ))
        } else {
            // 没有 COUI 运行时：手绘同款珊瑚红圆钮（40dp / #EE4235）
            val fabView = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFEE4235.toInt())
                }
                elevation = dp(6f)
                isClickable = true
                isFocusable = true
                setOnClickListener { restartWetype() }
            }
            fabView.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_restart)
                setColorFilter(0xFFFFFFFF.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(TextView(this@PanelActivity).apply {
                    text = "wetype-dark"
                    textSize = 26f
                    isSingleLine = true
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    paint.isFakeBoldText = true
                    setTextColor(
                        ContextCompat.getColor(this@PanelActivity, R.color.settings_text_main)
                    )
                    gravity = Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(20) })
                addView(fabView, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    rightMargin = dp(16)
                })
            }
            col.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40 + 6 + 6)
            ))
        }

        col.addView(View(this), LinearLayout.LayoutParams(1, dp(4)))
        return col
    }

    // ---------------------------------------------------------------- 底部

    private fun buildBottomScrim(): View {
        val base = getColor(R.color.settings_scrim) and 0x00FFFFFF
        val top = base or 0x00000000
        val bottom = base or (0xB3 shl 24)
        return View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(bottom, top)
            )
            isClickable = false
            isFocusable = false
        }
    }

    private fun buildTabBar(): View {
        val items = listOf(
            NativeBottomBar.Item(1, "应用", R.drawable.ic_tab_sun, R.drawable.ic_tab_sun_selected),
            NativeBottomBar.Item(2, "设置", R.drawable.ic_tab_set, R.drawable.ic_tab_set_selected),
        )
        val selectedId = if (tabIndex == 0) 1 else 2
        val native = NativeBottomBar.build(this, items, selectedId) { id ->
            switchTab(id == 1)
        }
        if (native != null) return native

        FkLog.w("回退手写底栏")
        return CouiFloatingBar(this).apply {
            selected = tabIndex
            onTabSelected = { i -> switchTab(i == 0) }
        }
    }

    private fun switchTab(toApps: Boolean) {
        if (toApps == (tabIndex == 0)) return
        val target = if (toApps) AppsActivity::class.java else SettingsPageActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---------------------------------------------------------------- 重启微信输入法

    private fun restartWetype() {
        toast("正在重启微信输入法…")
        Thread {
            val script = """
                BEFORE=${'$'}(pidof com.tencent.wetype com.tencent.wetype:hld 2>/dev/null)
                echo "BEFORE:${'$'}{BEFORE:-none}"
                am force-stop com.tencent.wetype >/dev/null 2>&1
                sleep 0.6
                for p in ${'$'}(pidof com.tencent.wetype com.tencent.wetype:hld 2>/dev/null); do kill -9 ${'$'}p 2>/dev/null; done
                sleep 1.2
                LEFT=${'$'}(pidof com.tencent.wetype com.tencent.wetype:hld 2>/dev/null)
                echo "RESULT:${'$'}{LEFT:-none}"
            """.trimIndent()
            var out = ""
            runCatching {
                val p = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .start()
                out = p.inputStream.bufferedReader().readText()
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            }.onFailure { out = "${it.javaClass.simpleName}: ${it.message}" }
            FkLog.d("restart wetype: ${out.replace('\n', '|').take(200)}")

            val before = out.lineSequence().firstOrNull { it.startsWith("BEFORE:") }
                ?.removePrefix("BEFORE:")?.trim()?.split(" ")?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
            val left = out.lineSequence().firstOrNull { it.startsWith("RESULT:") }
                ?.removePrefix("RESULT:")?.trim()
            val leftPids = left?.split(" ")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val stillOld = leftPids.intersect(before)

            val msg = when {
                out.contains("denied", true) || out.contains("not allowed", true) ||
                    out.contains("Permission", true) -> "重启失败：KernelSU 未授权本应用"
                out.contains("No such file", true) -> "重启失败：找不到 su"
                left == null -> "重启失败：su 无输出"
                left == "none" || leftPids.isEmpty() -> "已重启微信输入法"
                stillOld.isEmpty() -> "已重启微信输入法"
                else -> "重启失败：进程 ${stillOld.joinToString(" ")} 仍在运行"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    // ---------------------------------------------------------------- 工具

    protected fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    protected fun dp(value: Float): Float = value * resources.displayMetrics.density

    protected fun resolveIsLightTheme(context: Context): Boolean {
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        return try {
            ta.getBoolean(0, true)
        } finally {
            ta.recycle()
        }
    }
}
