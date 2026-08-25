package com.netless.app

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class NetlessForegroundService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	override fun onCreate() {
		super.onCreate()
		val manager = getSystemService(NotificationManager::class.java)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			manager.createNotificationChannel(NotificationChannel(CHANNEL, "Netless mesh", NotificationManager.IMPORTANCE_LOW))
		}
		startForeground(NOTIFICATION_ID, notification())
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
		.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
		.setContentTitle("Netless is running")
		.setContentText("Nearby mesh discovery is active")
		.setOngoing(true)
		.build()

	private companion object {
		const val CHANNEL = "netless-mesh"
		const val NOTIFICATION_ID = 1
	}
}
