package com.suby.app

import android.Manifest
import androidx.activity.ComponentActivity
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
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Tasks
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.renderer.SurfaceViewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.livekit.android.events.collect
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LiveCallActivity : ComponentActivity() {
    private lateinit var room: Room
    private lateinit var status: TextView
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
        status = TextView(this).apply {
            text = "SUBY • ${mode.uppercase()} CALL\nकमरे का नाम डालें और Connect दबाएँ"
        }
        roomInput = EditText(this).apply {
            hint = "Room name (दोनों users में एक जैसा)"
            setText("suby-room")
            isSingleLine = true
        }
        val join = Button(this).apply { text = "Connect / Start Call" }
        val leave = Button(this).apply { text = "End Call" }
        videoRenderer = SurfaceViewRenderer(this).apply {
            visibility = if (mode == "audio") View.GONE else View.VISIBLE
        }
        root.addView(status)
        root.addView(roomInput)
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
        if (mode != "audio") needs.add(Manifest.permission.CAMERA)
        val missing = needs.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 301)
            status.text = "कॉल शुरू करने के लिए अनुमति दें, फिर Connect दबाएँ।"
            return
        }

        val roomName = roomInput.text.toString().trim()
        if (!roomName.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) {
            status.text = "Room name में केवल English अक्षर, नंबर, _ या - रखें।"
            return
        }

        status.text = "Firebase login और सुरक्षित call token प्राप्त हो रहा है..."
        lifecycleScope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) {
                    val auth = FirebaseAuth.getInstance()
                    val user = auth.currentUser
                        ?: throw IllegalStateException("पहले SUBY में अपने verified account से Login करें। Anonymous sign-in बंद है।")
                    val idToken = Tasks.await(user.getIdToken(false)).token
                        ?: throw IllegalStateException("Firebase ID token missing")
                    requestCallToken(roomName, idToken)
                }

                launch {
                    room.events.collect { event ->
                        if (event is RoomEvent.TrackSubscribed && event.track is VideoTrack) {
                            (event.track as VideoTrack).addRenderer(videoRenderer)
                        }
                    }
                }

                status.text = "LiveKit से जुड़ रहे हैं..."
                room.connect(credentials.first, credentials.second)
                if (mode != "audio") room.initVideoRenderer(videoRenderer)
                room.localParticipant.setMicrophoneEnabled(true)
                if (mode != "audio") room.localParticipant.setCameraEnabled(true)
                status.text = "Connected • Room: $roomName"
            } catch (e: Exception) {
                status.text = "कनेक्शन नहीं हुआ: ${e.message ?: "Firebase / server settings जाँचें"}"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connectCall()
            } else {
                status.text = "Camera और Microphone की अनुमति के बिना कॉल शुरू नहीं हो सकती।"
            }
        }
    }

    private fun requestCallToken(roomName: String, firebaseIdToken: String): Pair<String, String> {
        val connection = (URL("https://suby-token-server.vercel.app/api/livekit-token")
            .openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $firebaseIdToken")
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            val body = JSONObject().put("roomName", roomName).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = try { JSONObject(response).optString("error") } catch (_: Exception) { "HTTP $code" }
                throw IllegalStateException(message)
            }
            val json = JSONObject(response)
            return Pair(json.getString("serverUrl"), json.getString("participantToken"))
        } finally {
            connection.disconnect()
        }
    }

    private fun disconnectCall() {
        lifecycleScope.launch {
            try {
                room.disconnect()
                status.text = "Call ended."
            } catch (_: Exception) {
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
