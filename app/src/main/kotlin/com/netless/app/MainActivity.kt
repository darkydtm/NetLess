package com.netless.app

import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
	private val permissionRequest = 100
	private val viewModel: ProfileViewModel by viewModels {
		ProfileViewModel.factory((application as NetlessApplication).container.identityRepository)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val container = (application as NetlessApplication).container
		if (requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
			ContextCompat.startForegroundService(this, Intent(this, NetlessForegroundService::class.java))
		}
		setContent { NetlessApp(viewModel, container) }
		val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
		if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), permissionRequest)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == permissionRequest && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
			ContextCompat.startForegroundService(this, Intent(this, NetlessForegroundService::class.java))
		}
	}

	private fun requiredPermissions(): List<String> = buildList {
		if (Build.VERSION.SDK_INT >= 31) {
			add("android.permission.BLUETOOTH_SCAN")
			add("android.permission.BLUETOOTH_CONNECT")
		}
		if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
		if (Build.VERSION.SDK_INT >= 33) add("android.permission.NEARBY_WIFI_DEVICES") else add("android.permission.ACCESS_FINE_LOCATION")
	}
}
