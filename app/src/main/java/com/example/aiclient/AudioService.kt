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
import java.util.concurrent.atomic.AtomicLong

class AudioService : Service() {

    companion object {
        private const val TAG = "AudioService"
        private const val SAMPLE_RATE = 24000
        private const val BLOCK_SIZE = 4800
        private const val WS_URL = "ws://192.168.1.100:3000/ws"

        // 無音と判断するまでの待機時間（ミリ秒）
        private const val SILENCE_THRESHOLD_MS = 1000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null

    private var isRunning = false
    private var sendJob: Job? = null

    @Volatile
    private var isPlayingAudio = false

    // 最後に音声データを受信した時刻をミリ秒単位で記録
    private val lastAudioReceivedTime = AtomicLong(0)

    // 無音期間をチェックするためのJob
    private var silenceCheckJob: Job? = null

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
                    if (!isPlayingAudio) {
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
                    } else {
                        // 再生中は送信データを捨てる
                        accumulated.clear()
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
                    // 音声受信時に isPlayingAudio を true にする
                    isPlayingAudio = true
                    lastAudioReceivedTime.set(System.currentTimeMillis())

                    // 無音チェック用のジョブを再起動
                    restartSilenceCheckJob()

                    audioTrack?.write(decoded, 0, decoded.size)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text")
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
}