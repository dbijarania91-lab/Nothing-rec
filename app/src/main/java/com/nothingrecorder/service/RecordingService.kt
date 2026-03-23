package com.nothingrecorder.service

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.nothingrecorder.R
import com.nothingrecorder.ui.MainActivity
import com.nothingrecorder.utils.RecordingConfig
import com.nothingrecorder.utils.ThermalMonitor
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"
        const val EXTRA_RESULT_CODE   = "RESULT_CODE"
        const val EXTRA_RESULT_DATA   = "RESULT_DATA"
        const val EXTRA_CONFIG        = "CONFIG"
        const val CHANNEL_ID          = "recording_channel"
        const val NOTIF_ID            = 1001

        // Hardware encoder name for Snapdragon 778G+ (c2 architecture)
        private const val HW_ENCODER_HEVC = "c2.qti.hevc.encoder"
        private const val HW_ENCODER_AVC  = "c2.qti.avc.encoder"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var outputFile: File? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private var isRecording = false
    private var startTimeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var thermalMonitor: ThermalMonitor? = null
    private var config: RecordingConfig? = null

    // Threads
    private var videoEncoderThread: Thread? = null
    private var audioThread: Thread? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        thermalMonitor = ThermalMonitor(this) { temp ->
            handleThermalEvent(temp)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_CONFIG, RecordingConfig::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONFIG)

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

                startForeground(NOTIF_ID, buildNotification("Recording..."))
                startRecording(resultCode, resultData)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent?) {
        val cfg = config ?: return
        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projManager.getMediaProjection(resultCode, resultData ?: return)

        // Register callback so projection doesn't die silently
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, handler)

        // Output file in Movies/NothingRecorder
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), "Recordings").also { it.mkdirs() }
        outputFile = File(dir, "REC_${timestamp}.mp4")

        try {
            setupVideoEncoder(cfg)
            setupMuxer()
            setupVirtualDisplay(cfg)
            if (cfg.recordInternalAudio) setupInternalAudio(cfg)
            if (cfg.recordMic)           setupMicAudio(cfg)
            isRecording = true
            startTimeMs = System.currentTimeMillis()
            thermalMonitor?.start()
            startVideoEncoderLoop()
            if (cfg.recordInternalAudio || cfg.recordMic) startAudioLoop(cfg)
            updateNotification("Recording • ${cfg.resolutionLabel} • ${cfg.fps}fps")
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    // ─── VIDEO ENCODER ────────────────────────────────────────────────────────

    private fun setupVideoEncoder(cfg: RecordingConfig) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            cfg.width, cfg.height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // keyframe every 1s
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // 778G+ specific: use main profile for best compat
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
            // Priority 0 = real-time encoding (critical for no-lag)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.fps)
            // Reduce latency
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }

        // Try hardware encoder first (778G+), fallback to default
        videoEncoder = try {
            MediaCodec.createByCodecName(HW_ENCODER_HEVC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        } catch (e: Exception) {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        }
        videoEncoder?.start()
    }

    private fun setupVirtualDisplay(cfg: RecordingConfig) {
        val surface = videoEncoder?.createInputSurface() ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NothingRecorder",
            cfg.width, cfg.height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler
        )
    }

    private fun setupMuxer() {
        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun startVideoEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        videoEncoderThread = Thread {
            while (isRecording) {
                val enc = videoEncoder ?: break
                val outIndex = enc.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = mediaMuxer!!.addTrack(enc.outputFormat)
                        maybeStartMuxer()
                    }
                    outIndex >= 0 -> {
                        val buffer = enc.getOutputBuffer(outIndex) ?: continue
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            enc.releaseOutputBuffer(outIndex, false)
                            continue
                        }
                        if (muxerStarted && bufferInfo.size > 0) {
                            bufferInfo.presentationTimeUs =
                                (System.currentTimeMillis() - startTimeMs) * 1000L
                            mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        }
                        enc.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        }.also { it.name = "VideoEncoderThread"; it.start() }
    }

    // ─── AUDIO ────────────────────────────────────────────────────────────────

    private fun setupInternalAudio(cfg: RecordingConfig) {
        // Internal audio playback capture (Android 10+)
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioUsage.GAME)
            .addMatchingUsage(AudioUsage.MEDIA)
            .addMatchingUsage(AudioUsage.UNKNOWN)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(cfg.audioSampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            cfg.audioSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        internalAudioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        internalAudioRecord?.startRecording()
    }

    private fun setupMicAudio(cfg: RecordingConfig) {
        val minBuf = AudioRecord.getMinBufferSize(
            cfg.audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        micAudioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            cfg.audioSampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
        micAudioRecord?.startRecording()
    }

    private fun startAudioLoop(cfg: RecordingConfig) {
        // Setup AAC encoder
        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, cfg.audioSampleRate, 2
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
            it.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }

        val bufSize = AudioRecord.getMinBufferSize(
            cfg.audioSampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4

        audioThread = Thread {
            val readBuf  = ShortArray(bufSize / 2)
            val mixBuf   = ShortArray(bufSize / 2)
            val bufInfo  = MediaCodec.BufferInfo()

            while (isRecording) {
                val aEnc = audioEncoder ?: break
                // Read internal audio
                val internalRead = if (cfg.recordInternalAudio)
                    internalAudioRecord?.read(readBuf, 0, readBuf.size) ?: 0 else 0

                // Read mic audio
                val micBuf = ShortArray(bufSize / 2)
                val micRead = if (cfg.recordMic)
                    micAudioRecord?.read(micBuf, 0, micBuf.size) ?: 0 else 0

                // Mix both sources
                val count = maxOf(internalRead, micRead)
                if (count > 0) {
                    for (i in 0 until count) {
                        val s1 = if (i < internalRead) readBuf[i].toInt() else 0
                        val s2 = if (i < micRead)      micBuf[i].toInt()  else 0
                        mixBuf[i] = (s1 + s2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }

                    val inIndex = aEnc.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val buf = aEnc.getInputBuffer(inIndex)
                        buf?.clear()
                        for (s in mixBuf.take(count)) buf?.putShort(s)
                        val pts = (System.currentTimeMillis() - startTimeMs) * 1000L
                        aEnc.queueInputBuffer(inIndex, 0, count * 2, pts, 0)
                    }
                }

                // Drain audio encoder
                var outIndex = aEnc.dequeueOutputBuffer(bufInfo, 0)
                while (outIndex >= 0) {
                    val outBuf = aEnc.getOutputBuffer(outIndex)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        aEnc.releaseOutputBuffer(outIndex, false)
                        outIndex = aEnc.dequeueOutputBuffer(bufInfo, 0)
                        continue
                    }
                    if (muxerStarted && outBuf != null && bufInfo.size > 0 && audioTrackIndex >= 0) {
                        mediaMuxer?.writeSampleData(audioTrackIndex, outBuf, bufInfo)
                    }
                    aEnc.releaseOutputBuffer(outIndex, false)
                    outIndex = aEnc.dequeueOutputBuffer(bufInfo, 0)
                }

                // Add audio track once format is known
                if (audioTrackIndex < 0) {
                    val fmt = aEnc.outputFormat
                    if (fmt.getString(MediaFormat.KEY_MIME) != null) {
                        audioTrackIndex = mediaMuxer!!.addTrack(fmt)
                        maybeStartMuxer()
                    }
                }
            }
        }.also { it.name = "AudioThread"; it.start() }
    }

    @Synchronized
    private fun maybeStartMuxer() {
        val needsAudio = config?.recordInternalAudio == true || config?.recordMic == true
        val audioReady = !needsAudio || audioTrackIndex >= 0
        if (!muxerStarted && videoTrackIndex >= 0 && audioReady) {
            mediaMuxer?.start()
            muxerStarted = true
        }
    }

    // ─── STOP ─────────────────────────────────────────────────────────────────

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        thermalMonitor?.stop()

        videoEncoderThread?.join(3000)
        audioThread?.join(2000)

        try {
            videoEncoder?.apply { signalEndOfInputStream(); stop(); release() }
            audioEncoder?.apply { stop(); release() }
            internalAudioRecord?.apply { stop(); release() }
            micAudioRecord?.apply { stop(); release() }
            virtualDisplay?.release()
            mediaProjection?.stop()
            if (muxerStarted) mediaMuxer?.stop()
            mediaMuxer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        videoEncoder = null; audioEncoder = null
        internalAudioRecord = null; micAudioRecord = null
        virtualDisplay = null; mediaProjection = null
        mediaMuxer = null; muxerStarted = false
        videoTrackIndex = -1; audioTrackIndex = -1

        // Add to MediaStore so it appears in Gallery
        outputFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATA, file.absolutePath)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        // Notify UI
        sendBroadcast(Intent("com.nothingrecorder.RECORDING_STOPPED").apply {
            putExtra("file_path", outputFile?.absolutePath ?: "")
        })
    }

    // ─── THERMAL GUARD ────────────────────────────────────────────────────────

    private fun handleThermalEvent(temp: Float) {
        if (temp >= 42f && config?.thermalGuard == true && config?.fps == 120) {
            // Drop from 120fps to 60fps to reduce heat
            config = config?.copy(fps = 60)
            updateNotification("Thermal Guard: Dropped to 60fps (${temp.toInt()}°C)")
        }
    }

    // ─── NOTIFICATION ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Screen Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active recording session" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NothingRecorder")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
