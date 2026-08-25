package com.netless.transport

import kotlinx.coroutines.flow.Flow

interface TransportAdapter {
	val type: TransportType
	val availability: Flow<TransportState>

	suspend fun connect(endpoint: TransportEndpoint): TransportConnection

	fun supports(capability: DiscoveryCapability): Boolean
	fun fail() { }
}
