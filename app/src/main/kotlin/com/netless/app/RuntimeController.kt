package com.netless.app

import com.netless.transport.DiscoveryTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RuntimeController(
	private val scope: CoroutineScope,
	private val ble: DiscoveryTransport,
	private val wifiDirect: DiscoveryTransport,
	private val contacts: ContactStore,
	private val audio: AudioRuntime,
	private val peerMessages: PeerMessageRuntime? = null,
) {
	private var discoveryJob: Job? = null
	private var localPort: Int = 0

	fun startDiscovery() {
		if (discoveryJob != null) return
		discoveryJob = scope.launch {
			localPort = peerMessages?.startServer(scope) ?: 0
			launch { ble.startDiscovery().collect(contacts::upsert) }
			launch { wifiDirect.startDiscovery().collect(contacts::upsert) }
		}
	}

	fun stopDiscovery() {
		discoveryJob?.cancel()
		discoveryJob = null
		scope.launch {
			ble.stopDiscovery()
			wifiDirect.stopDiscovery()
		}
	}

	fun startAudio() = audio.start()
	fun stopAudio() = audio.stop()
}
