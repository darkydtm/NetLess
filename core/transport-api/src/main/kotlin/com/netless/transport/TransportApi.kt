package com.netless.transport

import com.netless.common.NodeId
import kotlinx.coroutines.flow.Flow
import java.io.Serializable
import java.util.Collections

enum class TransportType {
	Bluetooth,
	WifiDirect,
	WifiAware,
	LocalHotspot,
}

enum class TransportState {
	Unavailable,
	Idle,
	Discovering,
	Connecting,
	Connected,
	Failed,
	Closed,
}

data class TransportCapabilities(
	val canAdvertise: Boolean,
	val canAcceptIncoming: Boolean,
	val maxConcurrentConnections: Int,
	val supportsRelay: Boolean,
	val supportsLowLatency: Boolean,
) : Serializable {
	init {
		require(maxConcurrentConnections >= 0) { "maxConcurrentConnections must not be negative" }
	}
}

class TransportEndpoint(
	val nodeId: NodeId,
	val address: String,
	metadata: Map<String, String> = emptyMap(),
) : Serializable {
	val metadata: Map<String, String> = Collections.unmodifiableMap(metadata.toMutableMap())

	init {
		require(address.isNotBlank()) { "address must not be blank" }
	}

	override fun equals(other: Any?): Boolean =
		other is TransportEndpoint &&
			nodeId == other.nodeId &&
			address == other.address &&
			metadata == other.metadata

	override fun hashCode(): Int = 31 * (31 * nodeId.hashCode() + address.hashCode()) + metadata.hashCode()
}

enum class DiscoveryCapability {
	Relay,
	LowLatency,
	Advertise,
	AcceptIncoming,
}

class DiscoveryAdvertisement(
	val discoveryHash: String,
	val protocolVersion: Int,
	val sessionId: String,
	capabilities: Set<DiscoveryCapability>,
	transportHints: Set<TransportType> = emptySet(),
) : Serializable {
	val capabilities: Set<DiscoveryCapability> = Collections.unmodifiableSet(capabilities.toMutableSet())
	val transportHints: Set<TransportType> = Collections.unmodifiableSet(transportHints.toMutableSet())

	init {
		require(discoveryHash.isNotBlank()) { "discoveryHash must not be blank" }
		require(protocolVersion > 0) { "protocolVersion must be positive" }
		require(sessionId.isNotBlank()) { "sessionId must not be blank" }
	}

	override fun equals(other: Any?): Boolean =
		other is DiscoveryAdvertisement &&
			discoveryHash == other.discoveryHash &&
			protocolVersion == other.protocolVersion &&
			sessionId == other.sessionId &&
			capabilities == other.capabilities &&
			transportHints == other.transportHints

	override fun hashCode(): Int {
		var result = discoveryHash.hashCode()
		result = 31 * result + protocolVersion
		result = 31 * result + sessionId.hashCode()
		result = 31 * result + capabilities.hashCode()
		result = 31 * result + transportHints.hashCode()
		return result
	}
}

data class DiscoveredNode(
	val nodeId: NodeId,
	val endpoint: TransportEndpoint,
	val capabilities: TransportCapabilities,
) : Serializable

interface DiscoveryTransport {
	suspend fun startDiscovery(): Flow<DiscoveredNode>
	suspend fun stopDiscovery()
	suspend fun advertise(advertisement: DiscoveryAdvertisement)
}

interface DataTransport {
	val type: TransportType
	val state: Flow<TransportState>

	suspend fun connect(endpoint: TransportEndpoint): TransportConnection
}

interface TransportConnection {
	val incomingPackets: Flow<ByteArray>

	suspend fun send(packet: ByteArray)
	suspend fun close()
}
