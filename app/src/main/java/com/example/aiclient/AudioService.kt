package com.example.aiclient

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

class AudioService : Service() {

    companion object {
        private const val TAG = "AudioService"
        private const val SAMPLE_RATE = 24000
        private const val BLOCK_SIZE = 4800
        private const val WS_URL = "ws://192.168.1.100:3000/ws" // 実際にアクセス可能なURLに変更すること
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null

    private var isRunning = false
    private var sendJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startAudioProcessing()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioProcessing()
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
        // AudioRecord初期化
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            Log.e(TAG, "RECORD_AUDIO permission not granted. Stopping service.")
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

        // AudioTrack初期化（スピーカー出力用）
        val outBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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

        // WebSocket接続
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

        // マイク入力→WebSocket送信
        audioRecord?.startRecording()
        sendJob = serviceScope.launch {
            val sendBuffer = ByteArray(BLOCK_SIZE)
            val accumulated = mutableListOf<Byte>()
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord!!.read(sendBuffer, 0, BLOCK_SIZE)
                if (read > 0) {
                    accumulated.addAll(sendBuffer.slice(0 until read))
                    if (accumulated.size >= BLOCK_SIZE) {
                        val toSend = accumulated.take(BLOCK_SIZE).toByteArray()
                        repeat(BLOCK_SIZE) { accumulated.removeAt(0) }
                        val base64data = Base64.encodeToString(toSend, Base64.NO_WRAP)
                        val json = JSONObject().apply {
                            put("type", "input_audio_buffer.append")
                            put("audio", base64data)
                        }
                        webSocket?.send(json.toString())
                    }
                }
            }
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            if (type == "response.audio.delta") {
                val delta = json.optString("delta", "")
                if (delta.isNotEmpty()) {
                    val decoded = Base64.decode(delta, Base64.NO_WRAP)
                    // PCMデータをAudioTrackへ書き込む
                    audioTrack?.write(decoded, 0, decoded.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text")
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

        isRunning = false
    }
}