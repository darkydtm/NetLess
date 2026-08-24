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

	fun start() {
		bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		if (bufferSize <= 0) error("Audio input unavailable")
		recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
		track = AudioTrack.Builder()
			.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
			.setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
			.setBufferSizeInBytes(bufferSize)
			.build()
		recorder?.startRecording()
		track?.play()
	}

	fun stop() {
		recorder?.stop()
		recorder?.release()
		recorder = null
		track?.stop()
		track?.release()
		track = null
	}
}
