package com.example.aiclient

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
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
import androidx.core.net.toUri
import org.json.JSONException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import android.content.Intent


class AudioService : Service() {
    companion object {
        const val TAG = "AudioService"

        // Audio constants
        const val SAMPLE_RATE = 24000
        const val BLOCK_SIZE = 4800
        const val SILENCE_THRESHOLD_MS = 2000L
        const val SILENCE_THRESHOLD = 50.0f

        // Actions and extras
        const val ACTION_START_PROCESSING = "com.example.aiclient.action.START_PROCESSING"
        const val ACTION_UPDATE_TEMPERATURE = "com.example.aiclient.UPDATE_TEMPERATURE"
        const val EXTRA_TEMPERATURE = "extra_temperature"
        const val ACTION_UPDATE_URL = "com.example.aiclient.action.UPDATE_URL"
        const val EXTRA_WEBSOCKET_URL = "com.example.aiclient.extra.WEBSOCKET_URL"

        // Audio ON/OFF
        const val ACTION_PAUSE_AUDIO_INPUT = "com.example.aiclient.action.PAUSE_AUDIO_INPUT"
        const val ACTION_RESUME_AUDIO_INPUT = "com.example.aiclient.action.RESUME_AUDIO_INPUT"

        // Broadcasts to notify MainActivity (or others) of state changes
        const val ACTION_STATE_CHANGED = "com.example.aiclient.action.STATE_CHANGED"
        const val EXTRA_AUDIO_ON = "extra_audio_on"
        const val EXTRA_WS_CONNECTED = "extra_ws_connected"

        // Notification channel/id
        const val FOREGROUND_CHANNEL_ID = "audio_service_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1

        // SharedPreferences key for client_id
        private const val PREFS_NAME = "audio_service_prefs"
        private const val KEY_CLIENT_ID = "key_client_id"
    }

    // Coroutine scope for background tasks
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null

    // Main flag
    private var isRunning = false
    private var sendJob: Job? = null

    // For demonstration, any data from extras
    private var temperature: Int = 20
    private var speed: Int = 0
    private var fuel: Int = 100
    private var latitude: Double = 35.6997837
    private var longitude: Double = 139.7741138
    private var address: String = "Unknown"
    private var timestamp: String = ""

    // Audio playback
    @Volatile
    private var isPlayingAudio = false
    private val lastAudioReceivedTime = AtomicLong(0)
    private var silenceCheckJob: Job? = null
    private val audioPlaybackQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private var audioPlaybackJob: Job? = null

    // Audio ON/OFF
    @Volatile
    private var isAudioSuspended: Boolean = false

    // WebSocket
    @Volatile
    private var wsConnected: Boolean = false
    private var websocketUrl: String = ""
    private var startUrl : String = ""
    private var clientId : String = ""

    // Reconnect settings
    private var reconnectDelayMs = 3000L  // Delay for re-connection
    private var reconnectJob: Job? = null

    // Dummy token
    private var token : String = "83031513-8AF4-4EF1-B18C-93087D2C6BCB"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionsIfNeeded()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(temperatureReceiver, IntentFilter(ACTION_UPDATE_TEMPERATURE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(temperatureReceiver, IntentFilter(ACTION_UPDATE_TEMPERATURE))
        }

        startForegroundService()
    }

    /**
     * Broadcast receiver to receive updated temperature from other components.
     */
    private val temperatureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_TEMPERATURE) {
                val newTemp = intent.getIntExtra(EXTRA_TEMPERATURE, temperature)
                temperature = newTemp
                Log.d(TAG, "Received temperature update: $temperature")
            }
        }
    }

    // -----------------------------
    // Utility
    // -----------------------------
    private fun getServerUrl(webSocketUrl: String): String {
        return if (webSocketUrl.startsWith("ws://") || webSocketUrl.startsWith("wss://")) {
            webSocketUrl.replace("ws://", "http://")
                .replace("wss://", "https://")
                .substringBeforeLast("/ws")
        } else {
            ""
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            websocketUrl = it.getStringExtra(EXTRA_WEBSOCKET_URL) ?: websocketUrl
            // Optional data
            temperature = it.getIntExtra("temp", temperature)
            speed = it.getIntExtra("speed", speed)
            fuel = it.getIntExtra("fuel", fuel)
            latitude = it.getDoubleExtra("latitude", latitude)
            longitude = it.getDoubleExtra("longitude", longitude)
            address = it.getStringExtra("address") ?: address
            timestamp = it.getStringExtra("timestamp") ?: timestamp

            // Update QR code URL
            startUrl = getServerUrl(websocketUrl) + "/start.html"

            when (it.action) {
                ACTION_START_PROCESSING -> {
                    if (!isRunning) {
                        isRunning = true
                        startAudioProcessing()
                    } else { ; }
                }
                ACTION_UPDATE_URL -> {
                    // If we get new URL
                    val newUrl = it.getStringExtra(EXTRA_WEBSOCKET_URL)
                    if (!newUrl.isNullOrEmpty()) {
                        websocketUrl = newUrl
                        restartWebSocket()
                    } else { ; }
                }
                ACTION_PAUSE_AUDIO_INPUT -> {
                    toggleAudioInput(false)
                }
                ACTION_RESUME_AUDIO_INPUT -> {
                    toggleAudioInput(true)
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

        // Connect WebSocket
        connectWebSocket()

        // Start recording
        audioRecord?.startRecording()

        // AudioPlayback Job（音声再生専用コルーチン）
        audioPlaybackJob = serviceScope.launch {
            try {
                while (isActive) {
                    // ここでタイムアウト付きブロッキング
                    val audioChunk = audioPlaybackQueue.poll(2000, TimeUnit.MILLISECONDS)
                    // データあれば再生開始
                    if (audioChunk == null) {
                        // 2秒間音声が来なかったら「再生中終了」とみなす
                        isPlayingAudio = false
                        if (!isAudioSuspended) {
                            showToast("Audio input is active.<AI is silent>")
                        } else {
                            showToast("Pausing audio input.<Please close the launched application>")
                        }
                        Log.d(TAG, "No audio chunk received for 2s. Marking playback as finished: isPlayingAudio = false")
                        continue  // breakせず待機継続（必要なら break にしてもOK）
                    } else {
                        isPlayingAudio = true
                        showToast("Pausing audio input.<AI is speaking>")
                        Log.d(TAG, "Audio chunk received. Marking playback as finished: isPlayingAudio = true")
                    }
                    audioTrack?.write(audioChunk, 0, audioChunk.size)
                    Log.d(TAG, "Audio chunk played: ${audioChunk.size} bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback loop error", e)
                isPlayingAudio = false
                showToast("Audio input is active.<Exception>")
            }
        }

        // サーバーから音声がくるまで受け付けない
        isPlayingAudio = true
        showToast("Pausing audio input.<waiting 1st sound from server>")
        Log.d(TAG, "waiting 1st sound from server: isPlayingAudio = true")

        // Mic reading job
        sendJob = serviceScope.launch {
            val sendBuffer = ByteArray(BLOCK_SIZE)
            var accumulated = mutableListOf<Byte>()
            var silenceDuration = 0L
            var lastUpdateTime = System.currentTimeMillis()
            val SILENCE_THRESHOLD_MILLIS = 1000L

            while (isActive) {
                if (isAudioSuspended) {
                    delay(200)
                    continue
                }

                val read = audioRecord?.read(sendBuffer, 0, BLOCK_SIZE) ?: -1
                if (read <= 0) {
                    delay(10) // 小さな遅延を入れてCPU使用率を下げる
                    continue
                }

                val rms = calculateRMS(sendBuffer, read)
                val currentTime = System.currentTimeMillis()

                // 無音時間を更新（read > 0 の場合のみ）
                silenceDuration = updateSilenceDuration(rms, silenceDuration, currentTime, lastUpdateTime)
                lastUpdateTime = currentTime

                // サーバーが話している or 無音状態なら送信をスキップ
                if (isPlayingAudio || isSilent(silenceDuration, SILENCE_THRESHOLD_MILLIS)) {
                    accumulated.clear()
                } else {
                    // バッファにデータ追加
                    accumulated.addAll(sendBuffer.slice(0 until read))

                    if (accumulated.size >= BLOCK_SIZE) {
                        val toSend = accumulated.take(BLOCK_SIZE).toByteArray()
                        accumulated = accumulated.drop(BLOCK_SIZE).toMutableList()
                        sendAudio(toSend)
                    }
                }
            }
        }
    }

    /**
     * Connects or reconnects the WebSocket, appending "?client_id=..." to the URL.
     */
    private fun connectWebSocket() {
        // If URL is invalid, skip
        if (!websocketUrl.startsWith("ws://") && !websocketUrl.startsWith("wss://")) {
            Log.e(TAG, "Invalid WebSocket URL: $websocketUrl")
            return
        }

        // Append client_id
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val finalUrl = if (clientId.isEmpty()) {
            "$websocketUrl?token=$token"
        } else {
            if (websocketUrl.contains("?")) {
                "$websocketUrl&client_id=$clientId&token=$token"
            } else {
                "$websocketUrl?client_id=$clientId&token=$token"
            }
        }
        Log.d(TAG, "WebSocket connecting to: $finalUrl")

        val client = OkHttpClient()
        val request = Request.Builder().url(finalUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                wsConnected = true
                Log.d(TAG, "WebSocket onOpen")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket onClosed: $code / $reason")
                wsConnected = false
                scheduleReconnect() // auto reconnect
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket onFailure: ${t.localizedMessage}")
                wsConnected = false
                scheduleReconnect()
            }
        })
    }

    /**
     * Schedule the reconnection after certain delay, unless already scheduled.
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            delay(reconnectDelayMs)
            Log.d(TAG, "Reconnecting WebSocket...")
            connectWebSocket()
        }
    }

    /**
     * Re-start WebSocket connection if needed.
     */
    private fun restartWebSocket() {
        webSocket?.close(1000, "Restarting WebSocket")
        webSocket = null
        wsConnected = false
        scheduleReconnect()
    }

    /**
     * Stop the entire audio processing.
     */
    private fun stopAudioProcessing() {
        reconnectJob?.cancel()
        sendJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioPlaybackJob?.cancel()
        audioPlaybackQueue.clear()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        webSocket?.close(1000, "Service stopped")
        webSocket = null

        silenceCheckJob?.cancel()
        isRunning = false
    }

    // ---------------------------
    // Audio I/O helper functions
    // ---------------------------
    private fun sendAudio(data: ByteArray) {
        if (webSocket == null) {
            Log.e(TAG, "WebSocket is null. Attempting to reconnect...")
            restartWebSocket()
            return
        }

        val base64data = Base64.encodeToString(data, Base64.NO_WRAP)
        if (base64data.isEmpty()) {
            Log.e(TAG, "Base64 encoding failed. Data is empty.")
            return
        }

        val json = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64data)
        }

        val jsonString = json.toString()
        val success = webSocket?.send(jsonString) ?: false
        if (!success) {
            Log.e(TAG, "Failed to send audio data. WebSocket might be disconnected.")
            restartWebSocket()
        } else {
            Log.d(TAG, "Sent audio data successfully.")
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

    private fun updateSilenceDuration(
        rms: Float,
        silenceDuration: Long,
        currentTime: Long,
        lastUpdateTime: Long,
    ): Long {
        return if (rms < SILENCE_THRESHOLD) {
            silenceDuration + (currentTime - lastUpdateTime)
        } else {
            // デバッグログ追加
            Log.d(TAG, "RMS: $rms, SilenceThreshold: $SILENCE_THRESHOLD")
            0L
        }
    }

    private fun sendVehicleDataAsJson(
        indoorTemperature: Int,
        speed: Int,
        fuel: Int,
        latitude: Double,
        longitude: Double,
        address: String,
        timestamp: String,
    ) {
        val vehicleStatusJson = JSONObject().apply {
            put("type", "vehicle_status")
            put("description", "This JSON represents the current vehicle status.")
            put("vehicle_data", JSONObject().apply {
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
            })
        }
        webSocket?.send(vehicleStatusJson.toString())
        Log.d(TAG, "Sent vehicle data JSON: $vehicleStatusJson.toString()")
    }

    /**
     * Check if a given string is a valid JSON format
     */
    private fun isJson(text: String): Boolean {
        return try {
            JSONObject(text)
            true
        } catch (ex: JSONException) {
            try {
                JSONArray(text)
                true
            } catch (ex2: JSONException) {
                false
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        Log.d(TAG, "handleIncomingMessage: $text")

        if (!isJson(text)) {
            Log.e(TAG, "Ignore non-JSON message: $text")
            return
        }
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type", "")) {
                "client_id" -> handleClientId(json)
                "response.audio.delta" -> handleAudioDelta(json)
                "tools.aircontrol" -> handleAirControl(json)
                "tools.aircontrol_delta" -> handleAirControlDelta(json)
                "tools.search_videos" -> handleSearchVideos(json)
                "tools.launch_navigation" -> handleLaunchNavigation(json)
                "proposal_video" -> handleProposalVideo(json)
                "proposal_ev_charge" -> handleProposalEvCharge(json)
                "demo_action" -> handleDemoAction(json)
                "stop_conversation" -> handleStopConversation(json)
                else -> Log.w(TAG, "Unhandled type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private fun openCustomTab(url: String, shouldPauseAudio: Boolean = true) {
        val finalUrl = appendTokenIfNeeded(url)
        val intent = Intent(this, CustomTabActivity::class.java).apply {
            putExtra("url", finalUrl)
            putExtra("pause_audio", shouldPauseAudio) // Pass the pause_audio flag
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(intent)
    }

    /**
     * Append token as query parameter if available.
     */
    private fun appendTokenIfNeeded(url: String): String {
        if (token.isEmpty()) return url

        val uri = url.toUri()
        val builder = uri.buildUpon().clearQuery()

        // クエリパラメータを一度全部コピーしつつ、token を除外
        for (param in uri.queryParameterNames) {
            if (param != "token") {
                for (value in uri.getQueryParameters(param)) {
                    builder.appendQueryParameter(param, value)
                }
            }
        }

        // token を追加（上書き or 新規追加のどちらにも対応）
        builder.appendQueryParameter("token", token)

        return builder.build().toString()
    }


    private fun handleClientId(json: JSONObject) {
        try {
            clientId = json.getString("client_id")
            val url = "$startUrl?target_id=$clientId"

            openCustomTab(url)

            Log.d(TAG, "Opened QR Code Page in Custom Tab: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open QR Code Page", e)
        }
    }

    private fun handleProposalVideo(json: JSONObject) {
        Log.e(TAG, "ProposalVideo:\n${json.toString(4)}")
        val videoUrl = json.optString("video_url", "")
        if (videoUrl.isNotEmpty()) {
            openCustomTab(videoUrl)
        } else {
            Log.e(TAG, "video_url is missing or empty")
        }
    }

    private fun handleProposalEvCharge(json: JSONObject) {
        Log.e(TAG, "ProposalEvCharge:\n${json.toString(4)}")
        this.handleLaunchNavigation(json)
    }

    /**
     * Stops current audio playback immediately.
     */
    private fun stopAudioPlayback() {
        audioPlaybackQueue.clear() // キュー内の音声データもクリア
        audioTrack?.pause()        // 再生中なら一時停止
        audioTrack?.flush()        // バッファをクリア
        Log.d(TAG, "stopAudioPlayback : audioTrack?.pause()")
        Log.d(TAG, "Audio playback stopped due to new DemoAction")

        audioTrack?.play()          // 次回データの再生のため
        Log.d(TAG, "stopAudioPlayback : audioTrack?.play()")

    }
    private fun handleDemoAction(json: JSONObject) {
        Log.d(TAG, "DemoAction:\n${json.toString(4)}")

        // 再生中の音声を停止
        stopAudioPlayback()

        val videoUrl = json.optString("video_url", "")
        if (videoUrl.isNotEmpty()) {
            openCustomTab(videoUrl)
        } else {
            Log.e(TAG, "video_url is missing or empty")
        }
    }
    private fun handleStopConversation(json: JSONObject) {
        Log.d(TAG, "StopConversation:\n${json.toString(4)}")

        // 再生中の音声を停止
        stopAudioPlayback()
    }

    private fun handleAudioDelta(json: JSONObject) {
        val delta = json.optString("delta", "")
        if (delta.isNotEmpty()) {
            val decoded = Base64.decode(delta, Base64.NO_WRAP)
            isPlayingAudio = true
            showToast("Pausing audio input.<server is speaking>")
            Log.d(TAG, "handleAudioDelta: isPlayingAudio = true")
            lastAudioReceivedTime.set(System.currentTimeMillis())
            restartSilenceCheckJob()
            audioPlaybackQueue.offer(decoded)
            Log.d(TAG, "Audio delta queued for playback: ${decoded.size} bytes")
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
            val newTemperature = temperature + temperatureDelta
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

        val intent = Intent(Intent.ACTION_VIEW, googleMapsUrl.toUri()).apply {
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
            data = youtubeSearchUrl.toUri()
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

    // ----------------------------
    // Silence detection for TTS
    // ----------------------------
    private fun restartSilenceCheckJob() {
        silenceCheckJob?.cancel()
        silenceCheckJob = serviceScope.launch {
            delay(SILENCE_THRESHOLD_MS)
            val now = System.currentTimeMillis()
            val lastTime = lastAudioReceivedTime.get()
            if (now - lastTime >= SILENCE_THRESHOLD_MS) {
                isPlayingAudio = false
                showToast("Audio input is active.<restartSilenceCheckJob>")
                Log.d(TAG, "No audio received recently, isPlayingAudio = false")
            }
        }
    }

    // -----------------------------
    // Audio ON/OFF toggling
    // -----------------------------
    private fun toggleAudioInput(on: Boolean) {
        if (!isRunning) return
        if (on) {
            // Resume
            if (isAudioSuspended) {
                audioRecord?.startRecording()
                isAudioSuspended = false
                showToast("Audio input is active.<toggleAudioInput>")
                Log.d(TAG, "Audio input resumed.")
            }
        } else {
            // Pause
            if (!isAudioSuspended) {
                audioRecord?.stop()
                isAudioSuspended = true
                showToast("Pausing audio input.")
                Log.d(TAG, "Audio input paused.")
            }
        }
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
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun requestPermissionsIfNeeded() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
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

    private fun showToast(message: String) {
        // UIスレッドでToastを表示
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            Log.d("MyService", "Toast shown: $message")
        }
    }
}