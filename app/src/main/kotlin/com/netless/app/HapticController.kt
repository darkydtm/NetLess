package com.netless.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class HapticController(context: Context) {
	private val preferences = context.getSharedPreferences("netless-settings", Context.MODE_PRIVATE)
	private val vibrator = context.getSystemService(Vibrator::class.java)

	var strength: Int
		get() = preferences.getInt(KEY_STRENGTH, DEFAULT_STRENGTH).coerceIn(0, 100)
		set(value) = preferences.edit().putInt(KEY_STRENGTH, value.coerceIn(0, 100)).apply()

	fun perform() {
		if (strength == 0 || vibrator?.hasVibrator() != true) return
		val amplitude = (strength * 255 / 100).coerceIn(1, 255)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			vibrator.vibrate(VibrationEffect.createOneShot(DURATION_MS, amplitude))
		} else {
			@Suppress("DEPRECATION") vibrator.vibrate(DURATION_MS)
		}
	}

	private companion object {
		const val KEY_STRENGTH = "haptic-strength"
		const val DEFAULT_STRENGTH = 50
		const val DURATION_MS = 18L
	}
}
