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

        // Check if we need to pause audio
        val shouldPauseAudio = intent.getBooleanExtra("pause_audio", true)
        if (shouldPauseAudio) {
            // Send PAUSE_AUDIO action to AudioService
            val pauseIntent = Intent(this, AudioService::class.java).apply {
                action = AudioService.ACTION_PAUSE_AUDIO_INPUT
            }
            startService(pauseIntent)
        }

        val customTabsIntent = CustomTabsIntent.Builder().build()
        // optional: no history, so it won't appear in recent apps
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        // Launch the URL via CustomTabs
        customTabsIntent.launchUrl(this, url.toUri())

        // Enable URL bar hiding (optional)
        customTabsIntent.intent.putExtra(CustomTabsIntent.EXTRA_ENABLE_URLBAR_HIDING, true)
    }

    override fun onResume() {
        super.onResume()

        // Check if this is second-time resume (means Chrome was closed)
        if (intent.getBooleanExtra("custom_tab_launched", false)) {
            // If we needed to pause audio on create, let's resume it now
            val shouldPauseAudio = intent.getBooleanExtra("pause_audio", false)
            if (shouldPauseAudio) {
                val resumeIntent = Intent(this, AudioService::class.java).apply {
                    action = AudioService.ACTION_RESUME_AUDIO_INPUT
                }
                startService(resumeIntent)
            }

            // Finally, close this Activity (the "middle man")
            finish()
        } else {
            // Mark that we have launched the custom tab
            intent.putExtra("custom_tab_launched", true)
        }
    }
}