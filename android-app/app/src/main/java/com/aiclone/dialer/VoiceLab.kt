@file:OptIn(ExperimentalLayoutApi::class)

package com.aiclone.dialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.aiclone.dialer.audio.ClipStore
import com.aiclone.dialer.audio.VoiceRecorder
import com.aiclone.dialer.audio.VoiceTools
import com.aiclone.dialer.data.VoiceProfile
import com.aiclone.dialer.net.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun labMicGate(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val action = pending; pending = null
        if (ok) action?.invoke() else Toast.makeText(ctx, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; launcher.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

private fun shareWav(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share voice clip"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Cannot share: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Voice Lab: (1) record a sentence, hear it in your cloned voice, keep the clips and compare original vs cloned later,
 * (2) Live monitor: speak and hear your cloned voice back almost in real time (like the other person would on a call).
 */
@Composable
fun VoiceLabDialog(p: VoiceProfile, backend: BackendClient, onTested: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = labMicGate()
    val recorder = remember { VoiceRecorder() }
    val liveRecorder = remember { VoiceRecorder() }
    var phase by remember { mutableStateOf("idle") }   // idle | recording | converting | playing
    var secs by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var clips by remember { mutableStateOf(ClipStore.list(ctx, p.id)) }
    var live by remember { mutableStateOf(false) }
    var liveInfo by remember { mutableStateOf("") }
    var liveJob by remember { mutableStateOf<Job?>(null) }

    fun stopLive() {
        liveRecorder.stop(); liveJob?.cancel(); liveJob = null; live = false
    }

    DisposableEffect(Unit) {
        onDispose { recorder.stop(); liveRecorder.stop(); liveJob?.cancel() }
    }

    fun startTest() = gate {
        stopLive()
        scope.launch {
            error = ""; secs = 0; phase = "recording"
            val pcm = try { recorder.record(20) { secs = it } } catch (e: Exception) { error = e.message ?: "Recording failed"; phase = "idle"; return@launch }
            if (pcm.size < 32000) { error = "Too short — speak for at least 2 seconds."; phase = "idle"; return@launch }
            phase = "converting"
            try {
                val out = backend.preview(p.id, pcm)
                onTested()
                withContext(Dispatchers.IO) { ClipStore.save(ctx, p.id, pcm, out) }
                clips = ClipStore.list(ctx, p.id)
                phase = "playing"
                VoiceTools.playPcm(out)
                phase = "idle"
            } catch (e: Exception) { error = e.message ?: "Conversion failed"; phase = "idle" }
        }
    }

    fun startLive() = gate {
        if (!live) {
            error = ""
            liveInfo = "Listening… speak normally. Use headphones!"
            live = true
            liveJob = scope.launch {
                val queue = Channel<Deferred<ByteArray?>>(8)
                launch {
                    for (d in queue) {
                        val b = d.await()
                        if (b != null) VoiceTools.playPcm(b)
                    }
                }
                try {
                    while (isActive) {
                        val pcm = liveRecorder.record(2)
                        if (!isActive) break
                        if (pcm.size < 32000) continue
                        val sentAt = System.currentTimeMillis()
                        queue.send(async {
                            try {
                                val out = backend.preview(p.id, pcm)
                                liveInfo = "Live ✓ conversion took ${System.currentTimeMillis() - sentAt} ms (plus the 2 s chunk)"
                                onTested()
                                out
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                liveInfo = e.message ?: "Conversion failed"
                                null
                            }
                        })
                    }
                } finally {
                    queue.close()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { recorder.stop(); stopLive(); onDismiss() },
        title = { Text("Voice Lab: ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Record & compare", style = MaterialTheme.typography.titleSmall)
                Text("Say a sentence (2–20 s). You hear it in your cloned voice and both clips are saved so you can compare them again later.", style = MaterialTheme.typography.bodySmall)
                when (phase) {
                    "recording" -> {
                        Text("● Recording… ${secs}s", color = MaterialTheme.colorScheme.error)
                        Button({ recorder.stop() }) { Text("Stop") }
                    }
                    "converting" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Converting to your cloned voice…") }
                    "playing" -> Text("▶ Playing your cloned voice…")
                    else -> Button({ startTest() }) { Text("Record & test") }
                }

                HorizontalDivider()
                Text("2. Live monitor (real time)", style = MaterialTheme.typography.titleSmall)
                Text("Speak and hear your cloned voice back in about 3–5 seconds, like the other person would on a call. Use headphones, otherwise the speaker feeds back into the mic. Every 2 s chunk uses ElevenLabs credits.", style = MaterialTheme.typography.bodySmall)
                if (live) Button({ stopLive() }) { Text("Stop live monitor") } else Button({ startLive() }) { Text("Start live monitor") }
                if (liveInfo.isNotBlank() && live) Text(liveInfo, style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                Text("Saved clips (${clips.size})", style = MaterialTheme.typography.titleSmall)
                if (clips.isEmpty()) Text("Nothing saved yet.", style = MaterialTheme.typography.bodySmall)
                clips.forEach { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(c.time)))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton({ scope.launch { VoiceTools.playPcm(ClipStore.pcm(c.cloned)) } }) { Text("▶ Cloned") }
                                TextButton({ scope.launch { VoiceTools.playPcm(ClipStore.pcm(c.original)) } }) { Text("▶ Original") }
                                TextButton({ shareWav(ctx, c.cloned) }) { Text("Share cloned") }
                                TextButton({ ClipStore.delete(c); clips = ClipStore.list(ctx, p.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ recorder.stop(); stopLive(); onDismiss() }) { Text("Close") } }
    )
}
