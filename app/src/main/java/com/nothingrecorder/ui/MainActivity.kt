package com.nothingrecorder.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nothingrecorder.R
import com.nothingrecorder.databinding.ActivityMainBinding
import com.nothingrecorder.service.RecordingService
import com.nothingrecorder.utils.RecordingConfig
import com.nothingrecorder.utils.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var isRecording = false

    // Timer
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            binding.tvTimer.text = formatTime(elapsedSeconds)
            binding.tvFileSize.text = estimateFileSize()
            handler.postDelayed(this, 1000)
        }
    }

    // MediaProjection result
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchRecordingService(result.resultCode, result.data)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Permissions
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) requestProjectionPermission()
        else Toast.makeText(this, "Permissions required to record", Toast.LENGTH_LONG).show()
    }

    // Stop broadcast receiver
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nothingrecorder.RECORDING_STOPPED") {
                onRecordingStopped(intent.getStringExtra("file_path"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setupUI()
        loadSavedSettings()
        registerReceiver(stopReceiver,
            IntentFilter("com.nothingrecorder.RECORDING_STOPPED"),
            RECEIVER_NOT_EXPORTED)
    }

    private fun setupUI() {
        // Record button
        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording()
            else startRecordingFlow()
        }

        // Resolution chips
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val res = when {
                checkedIds.contains(R.id.chip720p)  -> "720p"
                checkedIds.contains(R.id.chip2k)    -> "2K"
                else -> "1080p"
            }
            prefs.resolution = res
            updateBitrateHint()
        }

        // FPS chips
        binding.chipGroupFps.setOnCheckedStateChangeListener { _, checkedIds ->
            val fps = if (checkedIds.contains(R.id.chip120fps)) 120 else 60
            prefs.fps = fps
            updateFpsWarning(fps)
        }

        // Bitrate slider
        binding.sliderBitrate.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.bitrateMbps = value.toInt()
                binding.tvBitrateVal.text = "${value.toInt()} Mbps"
            }
        }

        // Audio toggles
        binding.switchInternalAudio.setOnCheckedChangeListener { _, checked ->
            prefs.internalAudio = checked
        }
        binding.switchMic.setOnCheckedChangeListener { _, checked ->
            prefs.mic = checked
        }
        binding.switchThermalGuard.setOnCheckedChangeListener { _, checked ->
            prefs.thermalGuard = checked
        }
    }

    private fun loadSavedSettings() {
        // Resolution
        when (prefs.resolution) {
            "720p"  -> binding.chip720p.isChecked  = true
            "2K"    -> binding.chip2k.isChecked    = true
            else    -> binding.chip1080p.isChecked = true
        }
        // FPS
        if (prefs.fps == 120) binding.chip120fps.isChecked = true
        else binding.chip60fps.isChecked = true

        // Bitrate
        binding.sliderBitrate.value = prefs.bitrateMbps.toFloat()
        binding.tvBitrateVal.text = "${prefs.bitrateMbps} Mbps"

        // Toggles
        binding.switchInternalAudio.isChecked  = prefs.internalAudio
        binding.switchMic.isChecked            = prefs.mic
        binding.switchThermalGuard.isChecked   = prefs.thermalGuard

        updateBitrateHint()
        updateFpsWarning(prefs.fps)
    }

    // ─── RECORDING FLOW ───────────────────────────────────────────────────────

    private fun startRecordingFlow() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)

        if (perms.isNotEmpty()) { permLauncher.launch(perms.toTypedArray()); return }
        requestProjectionPermission()
    }

    private fun requestProjectionPermission() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun launchRecordingService(resultCode: Int, data: Intent?) {
        val config = RecordingConfig.fromPrefs(
            resolution    = prefs.resolution,
            fps           = prefs.fps,
            bitrateMbps   = prefs.bitrateMbps,
            internalAudio = prefs.internalAudio,
            mic           = prefs.mic,
            thermalGuard  = prefs.thermalGuard
        )
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
            putExtra(RecordingService.EXTRA_CONFIG, config)
        }
        ContextCompat.startForegroundService(this, intent)
        onRecordingStarted()
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        })
    }

    private fun onRecordingStarted() {
        isRecording = true
        elapsedSeconds = 0
        binding.tvTimer.text = "00:00"
        binding.tvFileSize.text = "0 MB"
        binding.btnRecord.setText(R.string.stop)
        binding.btnRecord.setBackgroundColor(
            ContextCompat.getColor(this, R.color.rec_stop_color))
        binding.recDot.visibility = View.VISIBLE
        binding.tvStatus.setText(R.string.recording)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        binding.settingsGroup.visibility = View.GONE
        binding.tvFileSize.visibility = View.VISIBLE
        handler.post(timerRunnable)
    }

    private fun onRecordingStopped(filePath: String?) {
        isRecording = false
        handler.removeCallbacks(timerRunnable)
        binding.btnRecord.setText(R.string.start_recording)
        binding.btnRecord.setBackgroundColor(
            ContextCompat.getColor(this, R.color.accent_red))
        binding.recDot.visibility = View.GONE
        binding.tvStatus.setText(R.string.ready)
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.settingsGroup.visibility = View.VISIBLE
        binding.tvFileSize.visibility = View.GONE
        binding.tvTimer.text = "00:00"
        if (!filePath.isNullOrEmpty()) {
            Toast.makeText(this, "Saved: ${filePath.substringAfterLast('/')}", Toast.LENGTH_LONG).show()
        }
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    private fun updateBitrateHint() {
        val hint = when (prefs.resolution) {
            "2K"   -> "Recommended: 50-80 Mbps for 2K"
            "720p" -> "Recommended: 10-20 Mbps for 720p"
            else   -> "Recommended: 25-40 Mbps for 1080p"
        }
        binding.tvBitrateHint.text = hint
    }

    private fun updateFpsWarning(fps: Int) {
        binding.tvFpsNote.visibility = if (fps == 120) View.VISIBLE else View.GONE
    }

    private fun formatTime(s: Int): String {
        val m = s / 60; val sec = s % 60
        return "%02d:%02d".format(m, sec)
    }

    private fun estimateFileSize(): String {
        val bitsPerSec = prefs.bitrateMbps * 1_000_000L
        val bytes = (bitsPerSec / 8) * elapsedSeconds
        return if (bytes < 1_000_000_000) "${bytes / 1_000_000} MB"
        else "%.1f GB".format(bytes / 1_000_000_000.0)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        unregisterReceiver(stopReceiver)
        super.onDestroy()
    }
}
