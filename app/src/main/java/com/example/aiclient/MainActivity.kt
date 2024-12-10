package com.example.aiclient

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_RECORD_AUDIO = 1001
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startStopButton = findViewById<Button>(R.id.startStopButton)
        startStopButton.setOnClickListener {
            if (!isServiceRunning) {
                // 録音権限チェック
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQUEST_CODE_RECORD_AUDIO
                    )
                } else {
                    startMyService()
                }
            } else {
                stopMyService()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMyService()
            } else {
                // 権限がない場合の対応: ユーザーに権限が必要である旨を伝える等
            }
        }
    }

    private fun startMyService() {
        val intent = Intent(this, AudioService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServiceRunning = true
        findViewById<Button>(R.id.startStopButton).text = "Stop Service"
    }

    private fun stopMyService() {
        val intent = Intent(this, AudioService::class.java)
        stopService(intent)
        isServiceRunning = false
        findViewById<Button>(R.id.startStopButton).text = "Start Service"
    }
}