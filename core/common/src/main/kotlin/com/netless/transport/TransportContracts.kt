package com.netless.transport

import com.netless.common.NodeId
import java.io.Serializable
import java.util.Collections

enum class TransportType {
	Bluetooth,
	WifiDirect,
	WifiAware,
	LocalHotspot,
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
		other is TransportEndpoint && nodeId == other.nodeId && address == other.address && metadata == other.metadata

	override fun hashCode(): Int = 31 * (31 * nodeId.hashCode() + address.hashCode()) + metadata.hashCode()
}

sealed class TransportPolicy private constructor(
	val mode: TransportSelectionMode,
	preferences: List<TransportType>,
	val strictTransport: TransportType?,
	val relayAllowed: Boolean,
) : Serializable {
	val preferences: List<TransportType> = Collections.unmodifiableList(preferences.toList())
	val isStrict: Boolean
		get() = mode == TransportSelectionMode.Strict

	class Automatic(relayAllowed: Boolean = true) :
		TransportPolicy(TransportSelectionMode.Automatic, emptyList(), null, relayAllowed)

	class Preferred(preferences: List<TransportType>, relayAllowed: Boolean = true) :
		TransportPolicy(TransportSelectionMode.Preferred, preferences.distinct(), null, relayAllowed) {
		init {
			require(preferences.isNotEmpty()) { "preferences must not be empty" }
		}
	}

	class Strict(transport: TransportType?, relayAllowed: Boolean = true) :
		TransportPolicy(TransportSelectionMode.Strict, listOf(requireNotNull(transport)), requireNotNull(transport), relayAllowed)
}

enum class TransportSelectionMode {
	Automatic,
	Preferred,
	Strict,
}
