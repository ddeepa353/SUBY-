package com.suby.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.room.RoomEvent
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.renderer.SurfaceViewRenderer
import kotlinx.coroutines.launch

class LiveCallActivity : Activity() {
    private lateinit var room: Room
    private lateinit var status: TextView
    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var roomInput: EditText
    private lateinit var videoRenderer: SurfaceViewRenderer
    private var mode: String = "video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "video"
        room = LiveKit.create(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        status = TextView(this).apply { text = "SUBY • ${mode.uppercase()} CALL\nLiveKit credentials required" }
        roomInput = EditText(this).apply { hint = "Room name"; setText("suby-room") }
        serverInput = EditText(this).apply { hint = "LiveKit Server URL (wss://...)"; singleLine = true }
        tokenInput = EditText(this).apply { hint = "Participant token"; minLines = 3 }
        val join = Button(this).apply { text = "Connect / Start Call" }
        val leave = Button(this).apply { text = "End Call" }
        videoRenderer = SurfaceViewRenderer(this).apply {
            visibility = if (mode == "video") View.VISIBLE else View.GONE
        }
        root.addView(status)
        root.addView(roomInput)
        root.addView(serverInput)
        root.addView(tokenInput)
        root.addView(join)
        root.addView(leave)
        root.addView(videoRenderer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        join.setOnClickListener { connectCall() }
        leave.setOnClickListener { disconnectCall() }
    }

    private fun connectCall() {
        val needs = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (mode == "video") needs.add(Manifest.permission.CAMERA)
        val missing = needs.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 301)
            status.text = "कॉल शुरू करने के लिए अनुमति दें, फिर Connect दबाएँ।"
            return
        }

        val url = serverInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        val roomName = roomInput.text.toString().trim()
        if (!url.startsWith("wss://") || token.isBlank() || roomName.isBlank()) {
            status.text = "LiveKit Server URL, Room name और सुरक्षित participant token भरें।"
            return
        }

        status.text = "Connecting..."
        lifecycleScope.launch {
            try {
                launch {
                    room.events.collect { event ->
                        if (event is RoomEvent.TrackSubscribed && event.track is VideoTrack) {
                            (event.track as VideoTrack).addRenderer(videoRenderer)
                        }
                    }
                }
                room.connect(url, token)
                room.localParticipant.setMicrophoneEnabled(true)
                if (mode == "video") {
                    room.initVideoRenderer(videoRenderer)
                    room.localParticipant.setCameraEnabled(true)
                }
                status.text = "Connected to $roomName"
            } catch (e: Exception) {
                status.text = "Connection failed: ${e.message ?: "Check URL and token"}"
            }
        }
    }

    private fun disconnectCall() {
        lifecycleScope.launch {
            try {
                room.disconnect()
                status.text = "Call ended."
            } catch (e: Exception) {
                status.text = "Call ended."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            try { room.disconnect() } catch (_: Exception) { }
        }
    }
}
