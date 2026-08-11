package io.github.verybigsad.pimobile.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

fun interface MicrophonePermissionSource {
    fun current(): MicrophonePermissionState
}

class AndroidMicrophonePermissionSource(
    context: Context,
) : MicrophonePermissionSource {
    private val applicationContext = context.applicationContext

    override fun current(): MicrophonePermissionState =
        if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MicrophonePermissionState.GRANTED
        } else {
            MicrophonePermissionState.REQUEST_REQUIRED
        }
}

interface Pcm16AudioSource {
    fun start()

    fun read(destination: ShortArray, offset: Int, length: Int): Int

    fun stop()

    fun release()
}

fun interface Pcm16AudioSourceFactory {
    fun create(): Pcm16AudioSource
}

class AndroidAudioRecordSourceFactory(
    context: Context,
) : Pcm16AudioSourceFactory {
    private val applicationContext = context.applicationContext

    override fun create(): Pcm16AudioSource {
        check(applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "VOICE_PERMISSION_REQUIRED"
        }
        val minimumBytes = AudioRecord.getMinBufferSize(
            VoiceAudioSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minimumBytes > 0) { "VOICE_AUDIO_INITIALIZATION" }
        val bufferBytes = maxOf(minimumBytes, VoiceAudioSpec.BYTES_PER_FRAME * BUFFER_FRAME_COUNT)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(VoiceAudioSpec.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("VOICE_AUDIO_INITIALIZATION")
        }
        return AndroidAudioRecordSource(record)
    }

    private companion object {
        const val BUFFER_FRAME_COUNT = 4
    }
}

internal class AndroidAudioRecordSource(
    private val record: AudioRecord,
) : Pcm16AudioSource {
    private val lock = Any()
    private var released = false

    override fun start() {
        synchronized(lock) {
            check(!released) { "VOICE_AUDIO_RELEASED" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "VOICE_AUDIO_START" }
        }
    }

    override fun read(destination: ShortArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= destination.size)
        return record.read(destination, offset, length, AudioRecord.READ_BLOCKING)
    }

    override fun stop() {
        synchronized(lock) {
            if (!released && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } finally {
                try {
                    record.release()
                } finally {
                    released = true
                }
            }
        }
    }
}
