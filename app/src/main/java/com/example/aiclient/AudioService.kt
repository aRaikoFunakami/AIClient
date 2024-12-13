package com.example.aiclient

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong


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

    @Volatile
    private var isPlayingAudio = false

    // 最後に音声データを受信した時刻をミリ秒単位で記録
    private val lastAudioReceivedTime = AtomicLong(0)

    // 無音期間をチェックするためのJob
    private var silenceCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
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

            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord!!.read(sendBuffer, 0, BLOCK_SIZE)
                if (read > 0) {
                    if (isPlayingAudio) {
                        accumulated.clear()
                        //Log.d(TAG, "accumulated.clear()")
                    } else {
                        // 音声データを蓄積
                        accumulated.addAll(sendBuffer.slice(0 until read))
                        if (accumulated.size >= BLOCK_SIZE) {
                            val toSend = accumulated.take(BLOCK_SIZE).toByteArray()
                            repeat(BLOCK_SIZE) { accumulated.removeAt(0) }
                            sendAudio(toSend) // 通常の音声データを送信
                            //Log.d(TAG, "Sent sound data")
                        }else{
                            //Log.d(TAG, "accumulating sound data")
                        }
                    }
                }
            }

        }
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
    }

    private fun sendTemperatureToActivity(temp: Int) {
        val intent = Intent().apply {
            action = ACTION_UPDATE_TEMPERATURE
            putExtra(EXTRA_TEMPERATURE, temp)
        }
        sendBroadcast(intent)
    }
}