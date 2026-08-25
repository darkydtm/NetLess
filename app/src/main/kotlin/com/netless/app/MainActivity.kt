package com.netless.app

import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
	private val viewModel: ProfileViewModel by viewModels {
		ProfileViewModel.factory((application as NetlessApplication).container.identityRepository)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val container = (application as NetlessApplication).container
		ContextCompat.startForegroundService(this, Intent(this, NetlessForegroundService::class.java))
		setContent { NetlessApp(viewModel, container) }
	}
}
