package com.netless.app

import com.netless.transport.DiscoveryTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportType

class RuntimeController(
	private val scope: CoroutineScope,
	private val ble: DiscoveryTransport,
	private val wifiDirect: DiscoveryTransport,
	private val contacts: ContactStore,
	private val audio: AudioRuntime,
	private val peerMessages: PeerMessageRuntime? = null,
	private val meshRuntime: MeshRuntime? = null,
	private val advertisement: (Int) -> DiscoveryAdvertisement? = { null },
) {
	private var discoveryJob: Job? = null
	private var localPort: Int = 0

	fun startDiscovery() {
		if (discoveryJob != null) return
		discoveryJob = scope.launch {
			localPort = runCatching {
				kotlinx.coroutines.withContext(Dispatchers.IO) {
					peerMessages?.startServer(this) ?: 0
				}
			}.getOrDefault(0)
			advertisement(localPort)?.let {
				ble.advertise(it)
				wifiDirect.advertise(it)
			}
			launch { ble.startDiscovery().collect(contacts::upsert) }
			launch { wifiDirect.startDiscovery().collect(contacts::upsert) }
		}
	}

	fun stopDiscovery() {
		peerMessages?.stopServer()
		discoveryJob?.cancel()
		discoveryJob = null
		scope.launch {
			ble.stopDiscovery()
			wifiDirect.stopDiscovery()
		}
	}

	fun startAudio() = audio.start()
	fun stopAudio() = audio.stop()
	fun mesh(): MeshRuntime? = meshRuntime
}
