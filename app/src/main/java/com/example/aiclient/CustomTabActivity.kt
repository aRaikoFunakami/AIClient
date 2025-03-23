package com.example.aiclient

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

class CustomTabActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("url") ?: run {
            finish()
            return
        }

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        customTabsIntent.launchUrl(this, url.toUri())

        // CustomTabs の戻りを検知するためコールバック登録
        customTabsIntent.intent.putExtra(CustomTabsIntent.EXTRA_ENABLE_URLBAR_HIDING, true)
    }

    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("custom_tab_launched", false)) {
            // CustomTab閉じて戻ってきた判定
            finish()  // ここで中継アクティビティ即終了
        } else {
            intent.putExtra("custom_tab_launched", true)
        }
    }
}