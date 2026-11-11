package com.aiclone.dialer.audio

import android.content.Context
import java.io.File

/** Saves voice-test clips (your original + the cloned version) as WAV files inside the app's private storage. */
object ClipStore {
    data class Clip(val id: String, val time: Long, val original: File, val cloned: File)

    private fun dir(ctx: Context, profileId: String): File = File(ctx.filesDir, "clips/$profileId").apply { mkdirs() }

    fun save(ctx: Context, profileId: String, originalPcm: ByteArray, clonedPcm: ByteArray): Clip {
        val id = System.currentTimeMillis().toString()
        val d = dir(ctx, profileId)
        val o = File(d, "${id}_original.wav")
        val c = File(d, "${id}_cloned.wav")
        o.writeBytes(VoiceTools.wav(originalPcm))
        c.writeBytes(VoiceTools.wav(clonedPcm))
        return Clip(id, id.toLong(), o, c)
    }

    fun list(ctx: Context, profileId: String): List<Clip> {
        val files = dir(ctx, profileId).listFiles() ?: return emptyList()
        return files.filter { it.name.endsWith("_original.wav") }.mapNotNull { o ->
            val id = o.name.removeSuffix("_original.wav")
            val c = File(o.parentFile, "${id}_cloned.wav")
            val t = id.toLongOrNull()
            if (c.exists() && t != null) Clip(id, t, o, c) else null
        }.sortedByDescending { it.time }
    }

    fun delete(clip: Clip) { clip.original.delete(); clip.cloned.delete() }

    /** Raw PCM16 (strips the 44-byte WAV header). */
    fun pcm(file: File): ByteArray {
        val b = file.readBytes()
        return if (b.size > 44) b.copyOfRange(44, b.size) else ByteArray(0)
    }
}
