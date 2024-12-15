package com.example.aiclient

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val ACTION_UPDATE_TEMPERATURE = "com.example.aiclient.UPDATE_TEMPERATURE"
    private val REQUEST_CODE_RECORD_AUDIO = 1001
    private val REQUEST_LOCATION_PERMISSION = 1002

    private var isServiceRunning = false

    // 位置情報関連
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // GPS使用可否フラグ
    private var isGPSAvailable = false

    // 選択肢による位置
    // ACCESS本社座標
    private val ACCESS_LAT = 35.6997837
    private val ACCESS_LON = 139.7741138

    // ラスベガス・コンベンションセンター座標
    private val VEGAS_LAT = 36.1286087
    private val VEGAS_LON = -115.1515426

    private var currentLatitude = ACCESS_LAT
    private var currentLongitude = ACCESS_LON
    private var currentAddress: String = "Unknown"
    private var currentTimestamp: String = ""
    private var websocketUrl:String =  "ws://192.168.1.100:3000/ws"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startStopButton = findViewById<Button>(R.id.startStopButton)
        val tempPicker = findViewById<NumberPicker>(R.id.tempPicker)
        val speedPicker = findViewById<NumberPicker>(R.id.speedPicker)
        val fuelPicker = findViewById<NumberPicker>(R.id.fuelPicker)
        val addressTextView = findViewById<TextView>(R.id.addressTextView)
        val locationRadioGroup = findViewById<RadioGroup>(R.id.locationRadioGroup)
        val gpsOption = findViewById<RadioButton>(R.id.gpsRadioButton)
        val accessOption = findViewById<RadioButton>(R.id.accessRadioButton)
        val vegasOption = findViewById<RadioButton>(R.id.vegasRadioButton)
        val websocketUrlEditText = findViewById<EditText>(R.id.websocketUrlEditText)
        val updateWebsocketUrlButton = findViewById<Button>(R.id.updateWebsocketUrlButton)

        // テスト用にGoogle Play Services利用可能性チェック
        val googleAPI = GoogleApiAvailability.getInstance()
        val resultCode = googleAPI.isGooglePlayServicesAvailable(this)
        isGPSAvailable = (resultCode == ConnectionResult.SUCCESS)

        // websocket
        websocketUrl = websocketUrlEditText.text.toString().trim()

        // Picker設定
        tempPicker.minValue = 18
        tempPicker.maxValue = 30
        tempPicker.wrapSelectorWheel = true
        tempPicker.value = 20

        speedPicker.minValue = 0
        speedPicker.maxValue = 200
        speedPicker.wrapSelectorWheel = true
        speedPicker.value = 60

        fuelPicker.minValue = 0
        fuelPicker.maxValue = 100
        fuelPicker.wrapSelectorWheel = true
        fuelPicker.value = 50

        // ロケーション選択肢
        locationRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.accessRadioButton -> {
                    currentLatitude = ACCESS_LAT
                    currentLongitude = ACCESS_LON
                    updateLocationUI()
                    updateAudioServiceWithCurrentData()
                }
                R.id.vegasRadioButton -> {
                    currentLatitude = VEGAS_LAT
                    currentLongitude = VEGAS_LON
                    updateLocationUI()
                    updateAudioServiceWithCurrentData()
                }
                R.id.gpsRadioButton -> {
                    if (isGPSAvailable &&
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        updateLocationFromGPS()
                    } else {
                        Toast.makeText(this, "GPSが利用できません。", Toast.LENGTH_SHORT).show()
                        // GPS無効の場合、ACCESS本社をデフォルト選択に戻す
                        accessOption.isChecked = true
                    }
                }
            }
        }

        updateWebsocketUrlButton.setOnClickListener {
            websocketUrl = websocketUrlEditText.text.toString().trim()
            if (websocketUrl.isNotEmpty()) {
                updateWebSocketUrl(websocketUrl)
            }
        }

        // 初期はACCESS本社を選択
        accessOption.isChecked = true
        updateLocationUI()

        // start/stopボタン処理
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

        // 位置情報パーミッション要求
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
        }

        // Picker変更リスナー
        val pickerListener = { _: NumberPicker ->
            updateAudioServiceWithCurrentData()
        }
        tempPicker.setOnValueChangedListener { _, _, _ -> pickerListener(tempPicker) }
        speedPicker.setOnValueChangedListener { _, _, _ -> pickerListener(speedPicker) }
        fuelPicker.setOnValueChangedListener { _, _, _ -> pickerListener(fuelPicker) }
    }

    override fun onResume() {
        super.onResume()
        val intentFilter = IntentFilter(ACTION_UPDATE_TEMPERATURE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(temperatureReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
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

    private fun startMyService() {
        val intent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_START_PROCESSING // アクションを設定
            putExtra("temp", findViewById<NumberPicker>(R.id.tempPicker).value)
            putExtra("speed", findViewById<NumberPicker>(R.id.speedPicker).value)
            putExtra("fuel", findViewById<NumberPicker>(R.id.fuelPicker).value)
            putExtra("latitude", currentLatitude)
            putExtra("longitude", currentLongitude)
            putExtra("address", currentAddress)
            putExtra("timestamp", currentTimestamp)
            putExtra(AudioService.EXTRA_WEBSOCKET_URL, websocketUrl)
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

    private fun updateAudioServiceWithCurrentData() {
        val intent = Intent(this, AudioService::class.java).apply {
            putExtra("temp", findViewById<NumberPicker>(R.id.tempPicker).value)
            putExtra("speed", findViewById<NumberPicker>(R.id.speedPicker).value)
            putExtra("fuel", findViewById<NumberPicker>(R.id.fuelPicker).value)
            putExtra("latitude", currentLatitude)
            putExtra("longitude", currentLongitude)
            putExtra("address", currentAddress)
            putExtra("timestamp", currentTimestamp)
            putExtra(AudioService.EXTRA_WEBSOCKET_URL, websocketUrl)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    @SuppressLint("MissingPermission")
    private fun updateLocationFromGPS() {
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude
                    updateLocationUI()
                    updateAudioServiceWithCurrentData()
                } else {
                    Toast.makeText(this, "GPS位置情報が取得できません", Toast.LENGTH_SHORT).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this, "GPS位置情報取得失敗", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "GPS権限がありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLocationUI() {
        val addressTextView = findViewById<TextView>(R.id.addressTextView)
        currentAddress = getAddressFromLatLong(currentLatitude, currentLongitude) ?: "Unknown"
        addressTextView.text = currentAddress

        val timeZoneId = getTimeZoneIdFromLocation(currentLatitude, currentLongitude)
        val timeZone = TimeZone.getTimeZone(timeZoneId)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault()).apply {
            this.timeZone = timeZone
        }
        currentTimestamp = sdf.format(Date())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startMyService()
                }
            }
            REQUEST_LOCATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // GPS選択中なら再取得
                    val gpsOption = findViewById<RadioButton>(R.id.gpsRadioButton)
                    if (gpsOption.isChecked) {
                        updateLocationFromGPS()
                    }
                }
            }
        }
    }

    private fun getAddressFromLatLong(latitude: Double, longitude: Double): String? {
        val geocoder = Geocoder(this, Locale.getDefault())
        return try {
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                addresses[0].getAddressLine(0)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("GeocoderError", "Error: ${e.localizedMessage}")
            null
        }
    }

    private fun getTimeZoneIdFromLocation(latitude: Double, longitude: Double): String {
        return TimeZone.getDefault().id
    }

    private fun updateWebSocketUrl(newUrl: String) {
        val intent = Intent(this, AudioService::class.java).apply {
            action = AudioService.ACTION_UPDATE_URL
            putExtra(AudioService.EXTRA_WEBSOCKET_URL, newUrl)
        }
        startService(intent)
    }
}