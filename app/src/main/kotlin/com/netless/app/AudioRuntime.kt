package com.netless.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

class AudioRuntime {
	private val sampleRate = 16_000
	private var bufferSize = 0
	private var recorder: AudioRecord? = null
	private var track: AudioTrack? = null
	private var sequence = 0L

	fun start() {
		if (recorder != null || track != null) return
		bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		if (bufferSize <= 0) error("Audio input unavailable")
		recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
		if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
			recorder?.release()
			recorder = null
			error("Audio input unavailable")
		}
		track = AudioTrack.Builder()
			.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
			.setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
			.setBufferSizeInBytes(bufferSize)
			.build()
		if (track?.state != AudioTrack.STATE_INITIALIZED) {
			recorder?.release()
			recorder = null
			track?.release()
			track = null
			error("Audio output unavailable")
		}
		recorder?.startRecording()
		track?.play()
	}

	fun stop() {
		runCatching { recorder?.stop() }
		recorder?.release()
		recorder = null
		runCatching { track?.stop() }
		track?.release()
		track = null
	}

	fun captureFrame(): com.netless.transport.AudioPacket? {
		val source = recorder ?: return null
		val pcm = ShortArray(bufferSize / 2)
		val count = source.read(pcm, 0, pcm.size)
		if (count <= 0) return null
		val bytes = java.nio.ByteBuffer.allocate(count * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
		pcm.take(count).forEach(bytes::putShort)
		return com.netless.transport.AudioPacket(sequence++, System.currentTimeMillis(), bytes.array())
	}

	fun playFrame(packet: com.netless.transport.AudioPacket) {
		track?.write(packet.pcm, 0, packet.pcm.size)
	}
}
