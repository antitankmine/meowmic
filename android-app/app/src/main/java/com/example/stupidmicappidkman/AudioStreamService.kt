package com.example.stupidmicappidkman

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class AudioStreamService : Service() {

    private var isStreaming = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val frameSizeMs = 10
    private val samplesPerFrame = (sampleRate * frameSizeMs) / 1000
    private val bytesPerSample = 2
    private val pcmDataSize = samplesPerFrame * bytesPerSample // 960 bytes for 10ms at 48kHz
    
    private val headerSize = 9 // 1 byte ID + 8 bytes Seq
    private val packetSize = headerSize + pcmDataSize

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_IP = "EXTRA_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_PHONE_ID = "EXTRA_PHONE_ID"
        const val EXTRA_AUDIO_SOURCE = "EXTRA_AUDIO_SOURCE"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "AudioStreamChannel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_IP) ?: "192.168.1.100"
                val port = intent.getIntExtra(EXTRA_PORT, 7000)
                val phoneId = intent.getByteExtra(EXTRA_PHONE_ID, 1.toByte())
                val audioSource = intent.getIntExtra(EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC)
                val deviceId = if (intent.hasExtra(EXTRA_DEVICE_ID)) intent.getIntExtra(EXTRA_DEVICE_ID, -1) else null
                startStreaming(ip, port, phoneId, audioSource, deviceId)
            }
            ACTION_STOP -> {
                stopStreaming()
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    private fun startStreaming(ip: String, port: Int, phoneId: Byte, audioSource: Int, deviceId: Int?) {
        if (isStreaming.get()) return
        isStreaming.set(true)

        // Acquire WakeLock to keep CPU running when screen is off
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeowMic::StreamingWakeLock").apply {
            acquire(10 * 60 * 60 * 1000L /*10 hours*/)
        }

        createNotificationChannel()
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        thread(priority = Thread.MAX_PRIORITY) {
            try {
                val address = InetAddress.getByName(ip)
                socket = DatagramSocket()
                
                // Start a listener thread for pings
                thread {
                    val pingBuffer = ByteArray(1024)
                    while (isStreaming.get()) {
                        try {
                            val pingPacket = DatagramPacket(pingBuffer, pingBuffer.size)
                            socket?.receive(pingPacket)
                            val received = String(pingPacket.data, 0, pingPacket.length)
                            if (received.contains("ping", ignoreCase = true)) {
                                StreamState.addLog("${timeFormatter.format(Date())} RECEIVED: $received")
                                // Optional: Reply to ping
                                val responseData = "pong".toByteArray()
                                val responsePacket = DatagramPacket(responseData, responseData.size, pingPacket.address, pingPacket.port)
                                socket?.send(responsePacket)
                                StreamState.addLog("${timeFormatter.format(Date())} SENT: pong")
                            }
                        } catch (e: Exception) {
                            if (isStreaming.get()) Log.e("AudioStream", "Ping error", e)
                        }
                    }
                }

                val record = AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize.coerceAtLeast(pcmDataSize * 2),
                )
                audioRecord = record

                if ((deviceId != null) && (deviceId != -1)) {
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    devices.find { it.id == deviceId }?.let { preferredDevice ->
                        record.preferredDevice = preferredDevice
                    }
                }

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("AudioStream", "AudioRecord initialization failed")
                    return@thread
                }

                record.startRecording()
                
                val buffer = ByteArray(pcmDataSize)
                val packetData = ByteArray(packetSize)
                var sequenceNumber = 0L
                var lastLogTime = 0L

                while (isStreaming.get()) {
                    val read = audioRecord?.read(buffer, 0, pcmDataSize) ?: -1
                    if (read > 0) {
                        // Calculate peak amplitude for UI meter
                        var maxAmp = 0
                        var i = 0
                        while (i < (read - 1)) {
                            val low = buffer[i].toInt() and 0xFF
                            val high = buffer[i + 1].toInt() shl 8
                            val sample = (high or low).toShort().toInt()
                            val absSample = if (sample < 0) -sample else sample
                            if (absSample > maxAmp) maxAmp = absSample
                            i += 2
                        }
                        StreamState.amplitude.value = (maxAmp / 32768f).coerceIn(0f, 1f)

                        // Build packet: [ID(1)] + [Seq(8)] + [PCM]
                        packetData[0] = phoneId
                        
                        // Sequence number (Big-endian)
                        packetData[1] = (sequenceNumber shr 56).toByte()
                        packetData[2] = (sequenceNumber shr 48).toByte()
                        packetData[3] = (sequenceNumber shr 40).toByte()
                        packetData[4] = (sequenceNumber shr 32).toByte()
                        packetData[5] = (sequenceNumber shr 24).toByte()
                        packetData[6] = (sequenceNumber shr 16).toByte()
                        packetData[7] = (sequenceNumber shr 8).toByte()
                        packetData[8] = sequenceNumber.toByte()
                        
                        // PCM payload
                        System.arraycopy(buffer, 0, packetData, headerSize, read)
                        
                        val packet = DatagramPacket(packetData, headerSize + read, address, port)
                        socket?.send(packet)

                        // Log every ~500ms to avoid UI overload
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 500) {
                            StreamState.addLog("${timeFormatter.format(Date(now))} SENT: Seq $sequenceNumber (${headerSize + read} bytes)")
                            lastLogTime = now
                        }
                        
                        sequenceNumber++
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioStream", "Error streaming audio", e)
            } finally {
                cleanup()
            }
        }
    }

    private fun stopStreaming() {
        isStreaming.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e("AudioStream", "Error during cleanup", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming Service",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AudioStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Streaming")
            .setContentText("Streaming microphone to receiver...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
