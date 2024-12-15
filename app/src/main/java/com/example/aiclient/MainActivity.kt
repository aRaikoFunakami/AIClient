package com.example.aiclient

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Build
import android.widget.NumberPicker


class MainActivity : AppCompatActivity() {
    private val ACTION_UPDATE_TEMPERATURE = "com.example.aiclient.UPDATE_TEMPERATURE"
    private val REQUEST_CODE_RECORD_AUDIO = 1001
    private val REQUEST_LOCATION_PERMISSION = 1002
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startStopButton = findViewById<Button>(R.id.startStopButton)
        val tempPicker = findViewById<NumberPicker>(R.id.tempPicker)
        val speedPicker = findViewById<NumberPicker>(R.id.speedPicker)
        val fuelPicker = findViewById<NumberPicker>(R.id.fuelPicker)

        // プログラムで属性を設定
        tempPicker.minValue = 18
        tempPicker.maxValue = 30
        tempPicker.wrapSelectorWheel = true
        tempPicker.value = 20 // 初期値

        speedPicker.minValue = 0
        speedPicker.maxValue = 200
        speedPicker.wrapSelectorWheel = true
        speedPicker.value = 60 // 初期値

        fuelPicker.minValue = 0
        fuelPicker.maxValue = 100
        fuelPicker.wrapSelectorWheel = true
        fuelPicker.value = 50 // 初期値

        // Permission Check
        // Manifest.permission.RECORD_AUDIO
        startStopButton.setOnClickListener {
            if (!isServiceRunning) {
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
        // Manifest.permission.ACCESS_FINE_LOCATION
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        }

        // NumberPickerの変更をAudioServiceに通知
        val pickerListener = { picker: NumberPicker ->
            updateAudioService(picker)
        }
        tempPicker.setOnValueChangedListener { _, _, _ -> pickerListener(tempPicker) }
        speedPicker.setOnValueChangedListener { _, _, _ -> pickerListener(speedPicker) }
        fuelPicker.setOnValueChangedListener { _, _, _ -> pickerListener(fuelPicker) }
    }

    private fun startMyService() {
        val intent = Intent(this, AudioService::class.java).apply {
            putExtra("temp", findViewById<NumberPicker>(R.id.tempPicker).value)
            putExtra("speed", findViewById<NumberPicker>(R.id.speedPicker).value)
            putExtra("fuel", findViewById<NumberPicker>(R.id.fuelPicker).value)
        }
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

    override fun onResume() {
        super.onResume()

        val intentFilter = IntentFilter(ACTION_UPDATE_TEMPERATURE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13以降
            registerReceiver(temperatureReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Android 12以前
            registerReceiver(temperatureReceiver, intentFilter)
        }
    }

    private val temperatureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_TEMPERATURE) {
                val newTemperature = intent.getIntExtra("extra_temperature", 20)
                findViewById<NumberPicker>(R.id.tempPicker).value = newTemperature
            }
        }
    }

    private fun updateAudioService(picker: NumberPicker) {
        val intent = Intent(this, AudioService::class.java).apply {
            putExtra("temp", findViewById<NumberPicker>(R.id.tempPicker).value)
            putExtra("speed", findViewById<NumberPicker>(R.id.speedPicker).value)
            putExtra("fuel", findViewById<NumberPicker>(R.id.fuelPicker).value)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}