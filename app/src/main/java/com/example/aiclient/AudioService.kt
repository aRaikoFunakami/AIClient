package com.example.aiclient

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.sqrt

class AudioService : Service() {
    companion object {
        const val TAG = "AudioService"
        const val SAMPLE_RATE = 24000
        const val BLOCK_SIZE = 4800
        var websocketUrl = ""//""ws://192.168.1.100:3000/ws" // デフォルトURLを変数に変更
        const val SILENCE_THRESHOLD_MS = 2000L

        const val ACTION_START_PROCESSING = "com.example.aiclient.action.START_PROCESSING"
        const val ACTION_UPDATE_TEMPERATURE = "com.example.aiclient.UPDATE_TEMPERATURE"
        const val EXTRA_TEMPERATURE = "extra_temperature"
        const val ACTION_UPDATE_URL = "com.example.aiclient.action.UPDATE_URL"
        const val EXTRA_WEBSOCKET_URL = "com.example.aiclient.extra.WEBSOCKET_URL"
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

    private var latitude: Double = 35.6997837
    private var longitude: Double = 139.7741138
    private var address: String = "Unknown"
    private var timestamp: String = ""

    @Volatile
    private var isPlayingAudio = false

    private val lastAudioReceivedTime = AtomicLong(0)
    private var silenceCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        requestPermissionsIfNeeded()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(temperatureReceiver, IntentFilter(ACTION_UPDATE_TEMPERATURE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(temperatureReceiver, IntentFilter(ACTION_UPDATE_TEMPERATURE))
        }
        startForegroundService()
    }

    private var currentTemperature: Int = 20

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
            // 必要なエクストラを取得
            temp = it.getIntExtra("temp", 20)
            speed = it.getIntExtra("speed", 60)
            fuel = it.getIntExtra("fuel", 50)
            latitude = it.getDoubleExtra("latitude", 35.6997837)
            longitude = it.getDoubleExtra("longitude", 139.7741138)
            address = it.getStringExtra("address") ?: "Unknown"
            timestamp = it.getStringExtra("timestamp") ?: ""
            websocketUrl = it.getStringExtra(EXTRA_WEBSOCKET_URL) ?: websocketUrl
            when (it.action) {
                ACTION_START_PROCESSING -> {
                    // WebSocket URL のバリデーションを再確認
                    if (websocketUrl.isEmpty() || (!websocketUrl.startsWith("ws://") && !websocketUrl.startsWith("wss://"))) {
                        Log.e(TAG, "無効なWebSocket URL: $websocketUrl")
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    if (!isRunning) {
                        isRunning = true
                        startAudioProcessing()
                    } else {
                        Log.e(TAG, "isRunning: ${isRunning}")
                    }

                }

                ACTION_UPDATE_URL -> {
                    // WebSocket URL の更新処理
                    val newUrl = it.getStringExtra(EXTRA_WEBSOCKET_URL)
                    if (!newUrl.isNullOrEmpty()) {
                        websocketUrl = newUrl
                        // 必要に応じて WebSocket 接続を再構築
                        restartWebSocket()
                    }
                    else {
                        Log.e(TAG, "WS_URL: ${websocketUrl}")
                    }
                }

                else -> {
                    Log.w(TAG, "Unhandled action: ${it.action}")
                }
            }
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
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            stopSelf()
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

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

        Log.e(TAG, "WebSocket connect to : ${websocketUrl}")
        val client = OkHttpClient()
        val request = Request.Builder().url(websocketUrl).build()
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
            var isAudioSentRecently = false
            var lastUpdateTime = System.currentTimeMillis()
            val SILENCE_THRESHOLD_MILLIS = 1000L

            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord!!.read(sendBuffer, 0, BLOCK_SIZE)
                if (read > 0) {
                    val rms = calculateRMS(sendBuffer, read)
                    val currentTime = System.currentTimeMillis()
                    silenceDuration = updateSilenceDuration(rms, silenceDuration, currentTime, lastUpdateTime)

                    if (isPlayingAudio || isSilent(silenceDuration, SILENCE_THRESHOLD_MILLIS)) {
                        accumulated.clear()
                        isAudioSentRecently = false
                    } else {
                        accumulated.addAll(sendBuffer.slice(0 until read))
                        if (accumulated.size >= BLOCK_SIZE) {
                            val toSend = accumulated.take(BLOCK_SIZE).toByteArray()
                            repeat(BLOCK_SIZE) { accumulated.removeAt(0) }

                            if (!isAudioSentRecently) {
                                sendVehicleDataAsJson(temp, speed, fuel, latitude, longitude, address, timestamp)
                            }
                            sendAudio(toSend)
                            isAudioSentRecently = true
                        }
                    }

                    lastUpdateTime = currentTime
                }
            }
        }
    }

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

    private fun isSilent(silenceDuration: Long, durationThreshold: Long): Boolean {
        return silenceDuration >= durationThreshold
    }

    private fun calculateRMS(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            sum += buffer[i].toDouble().pow(2.0)
        }
        return sqrt(sum / length).toFloat()
    }

    private fun sendAudio(data: ByteArray) {
        val base64data = Base64.encodeToString(data, Base64.NO_WRAP)
        val json = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64data)
        }
        webSocket?.send(json.toString())
    }

    private fun sendVehicleDataAsJson(
        indoorTemperature: Int,
        speed: Int,
        fuel: Int,
        latitude: Double,
        longitude: Double,
        address: String,
        timestamp: String
    ) {
        val vehicleStatusJson = JSONObject().apply {
            put("description", "This JSON represents the current vehicle status.")
            put("speed", JSONObject().apply {
                put("value", speed)
                put("unit", "km/h")
            })
            put("indoor_temperature", JSONObject().apply {
                put("value", indoorTemperature)
                put("unit", "°C")
            })
            put("fuel_level", JSONObject().apply {
                put("value", fuel)
                put("unit", "%")
            })
            put("location", JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
            })
            put("address", address)
            put("timestamp", timestamp)
        }

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
                        put("text", vehicleStatusJson.toString())
                    })
                })
            })
        }

        webSocket?.send(messageJson.toString())
        Log.d(TAG, "Sent vehicle data JSON.")
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "response.audio.delta" -> handleAudioDelta(json)
                "tools.aircontrol" -> handleAirControl(json)
                "tools.aircontrol_delta" -> handleAirControlDelta(json)
                "tools.search_videos" -> handleSearchVideos(json)
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
            isPlayingAudio = true
            lastAudioReceivedTime.set(System.currentTimeMillis())
            restartSilenceCheckJob()
            audioTrack?.write(decoded, 0, decoded.size)
            Log.d(TAG, "Audio delta processed")
        }
    }

    private fun handleAirControl(json: JSONObject) {
        val intent = json.optJSONObject("intent")
        val aircontrol = intent?.optJSONObject("aircontrol")
        val temperature = aircontrol?.optInt("temperature", -1) ?: -1
        if (temperature != -1) {
            updateTemperature(temperature)
            Log.d(TAG, "Temperature updated via handleAirControl")
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
            Log.d(TAG, "Temperature updated via handleAirControlDelta")
        } else {
            Log.w(TAG, "Temperature delta is zero or not found")
        }
    }

    private fun handleLaunchNavigation(json: JSONObject) {
        Log.d(TAG, "call handleLaunchNavigation")
        val intent = json.optJSONObject("intent")?.optJSONObject("navigation")
        val destination = intent?.optString("destination", "").orEmpty()
        val lat = intent?.optDouble("latitude", Double.NaN) ?: Double.NaN
        val lon = intent?.optDouble("longitude", Double.NaN) ?: Double.NaN

        if (destination.isNotEmpty()) {
            launchGoogleMaps(destination = destination)
        } else if (!lat.isNaN() && !lon.isNaN()) {
            launchGoogleMaps(latitude = lat, longitude = lon)
        } else {
            Log.e(TAG, "Invalid navigation parameters.")
        }
    }

    private fun launchGoogleMaps(destination: String = "", latitude: Double = Double.NaN, longitude: Double = Double.NaN) {
        val googleMapsUrl = if (destination.isNotEmpty()) {
            "https://www.google.com/maps/dir/?api=1&destination=${android.net.Uri.encode(destination)}"
        } else if (!latitude.isNaN() && !longitude.isNaN()) {
            "https://www.google.com/maps/dir/?api=1&destination=$latitude,$longitude"
        } else {
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(googleMapsUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("org.chromium.chrome.stable")
        }

        try {
            startActivity(intent)
            Log.d(TAG, "Launching Google Maps with URL: $googleMapsUrl")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Chromium not found. Launching default browser.")
            intent.setPackage(null)
            startActivity(intent)
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
            Log.d(TAG, "Video search initiated: $input")
        } else {
            Log.e(TAG, "Invalid search or empty input.")
        }
    }

    private fun searchVideosOnYoutube(query: String) {
        val youtubeSearchUrl = "https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(youtubeSearchUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            intent.setPackage("org.chromium.chrome.stable")
            startActivity(intent)
            Log.d(TAG, "Launching Chromium with query: $query")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Chromium not found. Launching default browser.")
            intent.setPackage(null)
            startActivity(intent)
        }
    }

    private fun restartSilenceCheckJob() {
        silenceCheckJob?.cancel()
        silenceCheckJob = serviceScope.launch {
            delay(SILENCE_THRESHOLD_MS)
            val now = System.currentTimeMillis()
            val lastTime = lastAudioReceivedTime.get()
            if (now - lastTime >= SILENCE_THRESHOLD_MS) {
                isPlayingAudio = false
                Log.d(TAG, "No audio received recently, isPlayingAudio = false")
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

    private fun bringMainActivityToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun sendTemperatureToActivity(temp: Int) {
        val intent = Intent().apply {
            action = ACTION_UPDATE_TEMPERATURE
            putExtra(EXTRA_TEMPERATURE, temp)
            setPackage(packageName) // アプリ内に限定
        }
        sendBroadcast(intent)
    }

    private fun restartWebSocket() {
        webSocket?.close(1000, "URL updated")
        webSocket = null
        // 再接続
        startAudioProcessing()
    }

    private fun requestPermissionsIfNeeded() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
        )

        if (requiredPermissions.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            // 権限が付与されていない場合は、ユーザーにリクエストを送る必要があります。
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("request_permissions", requiredPermissions)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            stopSelf()
        }
    }
}