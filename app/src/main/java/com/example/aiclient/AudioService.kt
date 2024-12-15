package com.example.aiclient

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.sqrt

class AudioService : Service() {

    companion object {
        private const val TAG = "AudioService"
        private const val SAMPLE_RATE = 24000
        private const val BLOCK_SIZE = 4800
        private const val WS_URL = "ws://192.168.1.100:3000/ws"

        // 無音と判断するまでの待機時間（ミリ秒）
        private const val SILENCE_THRESHOLD_MS = 2000L

        // INTENT
        private const val ACTION_UPDATE_TEMPERATURE = "com.example.aiclient.UPDATE_TEMPERATURE"
        private const val EXTRA_TEMPERATURE = "extra_temperature"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null

    private var isRunning = false
    private var sendJob: Job? = null

    private var temp: Int = 20
    private var speed: Int = 0
    private var fuel: Int = 100
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    @Volatile
    private var isPlayingAudio = false

    // 最後に音声データを受信した時刻をミリ秒単位で記録
    private val lastAudioReceivedTime = AtomicLong(0)

    // 無音期間をチェックするためのJob
    private var silenceCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        // GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        @SuppressLint("MissingPermission", "UnsafeProtectedBroadcastReceiver")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13以降
            registerReceiver(temperatureReceiver, IntentFilter(ACTION_UPDATE_TEMPERATURE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Android 12以前
            registerReceiver(temperatureReceiver, IntentFilter(ACTION_UPDATE_TEMPERATURE))
        }
        startForegroundService()
    }

    private var currentTemperature: Int = 20 // 初期温度

    private val temperatureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_TEMPERATURE) {
                temp = intent.getIntExtra(EXTRA_TEMPERATURE, currentTemperature)
                Log.d(TAG, "Received temperature update: $temp")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            temp = it.getIntExtra("temp", -1)
            speed = it.getIntExtra("speed", -1)
            fuel = it.getIntExtra("fuel", -1)
        }
        if (!isRunning) {
            isRunning = true
            startAudioProcessing()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioProcessing()
        unregisterReceiver(temperatureReceiver)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundService() {
        val channelId = "audio_service_channel"
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("AIClient Audio Service Running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun startAudioProcessing() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted. Stopping service.")
            stopSelf()
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // AudioTrack初期化（スピーカー出力用）
        val outBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            outBufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()

        val client = OkHttpClient()
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket onFailure: ${t.localizedMessage}")
            }
        })

        audioRecord?.startRecording()
        sendJob = serviceScope.launch {
            val sendBuffer = ByteArray(BLOCK_SIZE)
            val accumulated = mutableListOf<Byte>()
            var silenceDuration = 0L
            var isAudioSentRecently = false // 直前に音声データが送信されたかを管理するフラグ
            var lastUpdateTime = System.currentTimeMillis()
            val SILENCE_THRESHOLD_MILLIS = 1000L

            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord!!.read(sendBuffer, 0, BLOCK_SIZE)
                if (read > 0) {
                    val rms = calculateRMS(sendBuffer, read)
                    val currentTime = System.currentTimeMillis()

                    // 無音状態の更新
                    silenceDuration = updateSilenceDuration(rms, silenceDuration, currentTime, lastUpdateTime)

                    if (isPlayingAudio || isSilent(silenceDuration, SILENCE_THRESHOLD_MILLIS)) {
                        resetAccumulation(accumulated)
                        isAudioSentRecently = false
                    } else {
                        addAudioDataToBuffer(accumulated, sendBuffer, read)
                        if (shouldSendAudio(accumulated)) {
                            sendBufferedAudio(accumulated, temp, speed, fuel, isAudioSentRecently)
                            isAudioSentRecently = true
                        }
                    }

                    lastUpdateTime = currentTime
                }
            }
        }
    }
    /**
     * 無音時間を更新する関数
     */
    private fun updateSilenceDuration(
        rms: Float,
        silenceDuration: Long,
        currentTime: Long,
        lastUpdateTime: Long
    ): Long {
        val SILENCE_THRESHOLD = 30.0f
        return if (rms < SILENCE_THRESHOLD) {
            silenceDuration + (currentTime - lastUpdateTime)
        } else {
            0L
        }
    }

    /**
     * 無音時にデータの蓄積をリセットする関数
     */
    private fun resetAccumulation(accumulated: MutableList<Byte>) {
        accumulated.clear()
    }

    /**
     * 音声データをバッファに追加する関数
     */
    private fun addAudioDataToBuffer(
        accumulated: MutableList<Byte>,
        sendBuffer: ByteArray,
        read: Int
    ) {
        accumulated.addAll(sendBuffer.slice(0 until read))
    }

    /**
     * バッファが送信条件を満たしているかを判定する関数
     */
    private fun shouldSendAudio(accumulated: MutableList<Byte>): Boolean {
        return accumulated.size >= BLOCK_SIZE
    }

    /**
     * バッファ内の音声データを送信し、必要に応じて車両データを送信する関数
     */
    private fun sendBufferedAudio(
        accumulated: MutableList<Byte>,
        temp: Int,
        speed: Int,
        fuel: Int,
        isAudioSentRecently: Boolean
    ) {
        val toSend = accumulated.take(BLOCK_SIZE).toByteArray()
        repeat(BLOCK_SIZE) { accumulated.removeAt(0) }

        if (!isAudioSentRecently) {
            sendVehicleDataAsJson(temp, speed, fuel)
        }
        sendAudio(toSend)
    }

    private fun isSilent(silenceDuration: Long, durationThreshold: Long): Boolean {
        return silenceDuration >= durationThreshold
    }
    // RMSを計算する関数
    private  fun calculateRMS(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i].toDouble().pow(2.0)
        }
        return sqrt(sum / length).toFloat()
    }

    /**
     * 音声データをBase64エンコードして送信
     */
    private fun sendAudio(data: ByteArray) {

        val base64data = Base64.encodeToString(data, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64data)
        }
        webSocket?.send(json.toString())
    }

    private fun sendVehicleDataAsJson(indoorTemperature: Int, speed: Int, fuel: Int) {
        getCurrentLocation { latitude, longitude ->
            // 緯度・経度から住所を取得
            val address = getAddressFromLatLong(this, latitude, longitude)

            // 緯度経度からタイムゾーンIDを取得し、それを TimeZone オブジェクトに変換
            val timeZoneId = getTimeZoneIdFromLocation(this, latitude, longitude)
            val timeZone = TimeZone.getTimeZone(timeZoneId) // String を TimeZone に変換

            // タイムゾーンを設定して ISO 8601形式のタイムスタンプを生成
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault()).apply {
                this.timeZone = timeZone // TimeZone オブジェクトを設定
            }
            val currentDateTime = sdf.format(Date())

            // 車両ステータスのJSONを作成
            val vehicleStatusJson = JSONObject().apply {
                put(
                    "description",
                    """This JSON represents the current vehicle status, 
                        including details such as speed, indoor temperature, 
                        fuel level, geographic location, and a timestamp.
                        """
                )
                put("speed", JSONObject().apply {
                    put("value", speed)
                    put("unit", "km/h") // 速度の単位
                })
                put("indoor_temperature", JSONObject().apply {
                    put("value", indoorTemperature)
                    put("unit", "°C") // 室温の単位
                })
                put("fuel_level", JSONObject().apply {
                    put("value", fuel)
                    put("unit", "%") // 燃料レベルの単位
                })
                put("location", JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                })
                put("address", address ?: "Unknown") // 住所を追加（取得できなかった場合は "Unknown" を設定）
                put("timestamp", currentDateTime)
            }

            // WebSocket送信用のメッセージJSONを作成
            val messageJson = JSONObject().apply {
                put("event_id", "event_${System.currentTimeMillis()}")
                put("type", "conversation.item.create")
                put("previous_item_id", JSONObject.NULL)
                put("item", JSONObject().apply {
                    put("id", "msg_${System.currentTimeMillis()}")
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_text")
                            put("text", vehicleStatusJson.toString()) // JSONを文字列として挿入
                        })
                    })
                })
            }

            // WebSocket送信
            webSocket?.send(messageJson.toString())
            Log.d(TAG, "Sent vehicle data with timezone: ${timeZone.id}")
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                // Receive audio from open ai via Copilot
                "response.audio.delta" -> handleAudioDelta(json)
                // Receive an order of air control
                "tools.aircontrol" -> handleAirControl(json)
                "tools.aircontrol_delta" -> handleAirControlDelta(json)
                // Receive an order of video search
                "tools.search_videos" -> handleSearchVideos(json)
                // Receive an order of launching navigation
                "tools.launch_navigation" -> handleLaunchNavigation(json)
                else -> Log.w(TAG, "Unhandled type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private fun handleAudioDelta(json: JSONObject) {
        val delta = json.optString("delta", "")
        if (delta.isNotEmpty()) {
            val decoded = Base64.decode(delta, Base64.NO_WRAP)
            // 音声受信時に isPlayingAudio を true にする
            isPlayingAudio = true
            lastAudioReceivedTime.set(System.currentTimeMillis())

            // 無音チェック用のジョブを再起動
            restartSilenceCheckJob()

            audioTrack?.write(decoded, 0, decoded.size)
            Log.d(TAG, "Audio delta processed")
        }
    }

    private fun handleSearchVideos(json: JSONObject) {
        val intentJson = json.optJSONObject("intent")
        val webBrowser = intentJson?.optJSONObject("webbrowser")
        val searchVideos = webBrowser?.optJSONObject("search_videos")
        val service = searchVideos?.optString("service", "youtube")
        val input = searchVideos?.optString("input", "")

        if (service == "youtube" && !input.isNullOrEmpty()) {
            searchVideosOnYoutube(input)
            Log.d(TAG, "Video search initiated for query: $input")
        } else {
            Log.e(TAG, "Invalid search intent or input is empty.")
        }
    }

    private fun handleAirControl(json: JSONObject) {
        val intent = json.optJSONObject("intent")
        val aircontrol = intent?.optJSONObject("aircontrol")
        val temperature = aircontrol?.optInt("temperature", -1) ?: -1

        if (temperature != -1) {
            updateTemperature(temperature)
            Log.d(TAG, "Temperature updated to $temperature via handleAirControl")
        } else {
            Log.w(TAG, "Temperature not found in aircontrol intent")
        }
    }

    private fun handleAirControlDelta(json: JSONObject) {
        val intent = json.optJSONObject("intent")
        val aircontrolDelta = intent?.optJSONObject("aircontrol_delta")
        val temperatureDelta = aircontrolDelta?.optInt("temperature_delta", 0) ?: 0

        if (temperatureDelta != 0) {
            val newTemperature = temp + temperatureDelta
            updateTemperature(newTemperature)
            Log.d(TAG, "Temperature updated to $newTemperature via handleAirControlDelta")
        } else {
            Log.w(TAG, "Temperature delta is zero or not found in aircontrol_delta intent")
        }
    }

    private fun handleLaunchNavigation(json: JSONObject) {
        Log.d(TAG, "call handleLaunchNavigation")
        val intent = json.optJSONObject("intent")?.optJSONObject("navigation")
        val destination = intent?.optString("destination", "").orEmpty()
        val latitude = intent?.optDouble("latitude", Double.NaN) ?: Double.NaN
        val longitude = intent?.optDouble("longitude", Double.NaN) ?: Double.NaN

        if (destination.isNotEmpty()) {
            // destinationが指定されている場合
            launchGoogleMaps(destination = destination)
        } else if (!latitude.isNaN() && !longitude.isNaN()) {
            // 緯度経度が指定されている場合
            launchGoogleMaps(latitude = latitude, longitude = longitude)
        } else {
            Log.e(TAG, "Invalid navigation parameters: destination, latitude, and longitude are all missing or invalid.")
        }
    }

    private fun launchGoogleMaps(destination: String = "", latitude: Double = Double.NaN, longitude: Double = Double.NaN) {
        val googleMapsUrl = if (destination.isNotEmpty()) {
            // destinationを優先
            "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}"
        } else if (!latitude.isNaN() && !longitude.isNaN()) {
            // 緯度経度を使用
            "https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude"
        } else {
            Log.e(TAG, "Unable to launch Google Maps: no valid destination or coordinates provided.")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Serviceから起動するためのフラグ
            setPackage("org.chromium.chrome.stable") // Chromiumを指定
        }

        try {
            startActivity(intent)
            Log.d(TAG, "Launching Google Maps with URL: $googleMapsUrl")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Chromium not found. Launching default browser.")
            // Chromiumが見つからない場合はデフォルトブラウザを使用
            intent.setPackage(null)
            startActivity(intent)
        }
    }

    /**
     * YouTubeで動画を検索するためにChromiumブラウザを起動
     */
    private fun searchVideosOnYoutube(query: String) {
        try {
            // YouTubeの検索URLを生成
            val youtubeSearchUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(query)

            // Intentを作成
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(youtubeSearchUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Serviceから起動するためのフラグ
            }

            // Chromiumを優先的に起動
            try {
                intent.setPackage("org.chromium.chrome.stable") // Chromiumのパッケージ名を設定
                startActivity(intent)
                Log.d(TAG, "Launching Chromium with query: $query")
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Chromium not found. Launching default browser.")
                // デフォルトブラウザにフォールバック
                intent.setPackage(null) // パッケージ名をリセット
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser for YouTube search.", e)
        }
    }

    private fun restartSilenceCheckJob() {
        silenceCheckJob?.cancel()
        silenceCheckJob = serviceScope.launch {
            // 一定時間（SILENCE_THRESHOLD_MS）後に新規音声がなければ isPlayingAudio = false
            delay(SILENCE_THRESHOLD_MS)
            val now = System.currentTimeMillis()
            val lastTime = lastAudioReceivedTime.get()
            if (now - lastTime >= SILENCE_THRESHOLD_MS) {
                isPlayingAudio = false
                Log.d(TAG, "No audio received for $SILENCE_THRESHOLD_MS ms, isPlayingAudio = false")
            } else {
                // もし遅延中にまた音声が来ていたら、何もしない（音声受信時にまた呼び出される）
            }
        }
    }

    private fun stopAudioProcessing() {
        sendJob?.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        webSocket?.close(1000, "Service stopped")
        webSocket = null

        silenceCheckJob?.cancel()

        isRunning = false
    }

    private fun updateTemperature(temp: Int) {
        Log.d(TAG, "Temperature updated to $temp")
        sendTemperatureToActivity(temp)
        bringMainActivityToFront()
    }

    /**
     * MainActivityを全面に出すためのメソッド
     */
    private fun bringMainActivityToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 新しいタスクとして起動
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) // 同じアクティビティがスタックにあればそれを再利用
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) // 同じアクティビティがフォアグラウンドにある場合に再作成しない
        }
        startActivity(intent)
    }

    private fun sendTemperatureToActivity(temp: Int) {
        val intent = Intent().apply {
            action = ACTION_UPDATE_TEMPERATURE
            putExtra(EXTRA_TEMPERATURE, temp)
        }
        sendBroadcast(intent)
    }

    private fun getCurrentLocation(onLocationReceived: (latitude: Double, longitude: Double) -> Unit) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted.")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                onLocationReceived(location.latitude, location.longitude)
            } else {
                Log.e(TAG, "Failed to get location.")
                onLocationReceived(Double.NaN, Double.NaN)
            }
        }
    }

    fun getTimeZoneIdFromLocation(context: Context, latitude: Double, longitude: Double): String {
        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            // Geocoderで住所情報を取得
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                // タイムゾーンIDを取得
                TimeZone.getDefault().id // デフォルト（バックアップ用）
            } else {
                Log.e("TimeZoneError", "No address found for location.")
                "UTC" // デフォルト値として UTC
            }
        } catch (e: Exception) {
            Log.e("TimeZoneError", "Error fetching timezone: ${e.localizedMessage}")
            "UTC" // エラー時のデフォルト
        }
    }

    fun getAddressFromLatLong(context: Context, latitude: Double, longitude: Double): String? {
        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (addresses != null && addresses.isNotEmpty()) {
                addresses[0].getAddressLine(0) // フォーマットされた住所を取得
            } else {
                null // 住所が見つからない場合
            }
        } catch (e: Exception) {
            Log.e("GeocoderError", "Error fetching address: ${e.localizedMessage}")
            null
        }
    }
}