package com.netless.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
	private val viewModel: ProfileViewModel by viewModels {
		ProfileViewModel.factory((application as NetlessApplication).container.identityRepository)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent { NetlessApp(viewModel) }
	}
}
