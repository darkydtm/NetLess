package com.netless.app

import com.netless.transport.DiscoveryTransport
import com.netless.transport.DiscoveryAdvertisement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class RuntimeControllerTest {
	@Test
	fun startsAndStopsBothDiscoveryTransports() = runBlocking {
		val controller = RuntimeController(
			CoroutineScope(Dispatchers.Unconfined),
			NoopDiscoveryTransport,
			NoopDiscoveryTransport,
			ContactStore(),
			AudioRuntime(),
		)
		controller.startDiscovery()
		controller.stopDiscovery()
	}
}

private object NoopDiscoveryTransport : DiscoveryTransport {
	override suspend fun startDiscovery() = emptyFlow<com.netless.transport.DiscoveredNode>()
	override suspend fun stopDiscovery() = Unit
	override suspend fun advertise(advertisement: DiscoveryAdvertisement) = Unit
}
