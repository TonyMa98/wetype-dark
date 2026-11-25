package com.tony.wetypedark.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 兼容壳：1119 之前用独立 Activity 承载「设置」页，重做后两栏都在
 * [AppsActivity] 的 Compose 页面里。外部若有旧入口指到这里，
 * 直接带 tab=1 转进主界面，避免出现两套设置 UI。
 */
class SettingsPageActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, AppsActivity::class.java)
                .putExtra(AppsActivity.EXTRA_TAB, 1)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
        finish()
    }
}
