package com.netless.common

import java.io.Serializable

@JvmInline
value class NodeId(val value: String) : Serializable {
	init {
		require(value.isNotBlank()) { "NodeId must not be blank" }
	}
}

@JvmInline
value class PacketId(val value: String) : Serializable {
	init {
		require(value.isNotBlank()) { "PacketId must not be blank" }
	}
}

@JvmInline
value class ProfileId(val value: String) : Serializable {
	init {
		require(value.isNotBlank()) { "ProfileId must not be blank" }
	}
}

enum class TrafficClass {
	Reliable,
	Bulk,
	Realtime,
}

enum class TransferMode {
	Speed,
	Balanced,
	Coverage,
}

data class TransferPolicy(
	val mode: TransferMode = TransferMode.Balanced,
	val maxHops: Int = 2,
	val ttlSeconds: Long = 86_400,
	val relayEnabled: Boolean = true,
) : Serializable {
	init {
		require(maxHops >= 0) { "maxHops must not be negative" }
		require(ttlSeconds > 0) { "ttlSeconds must be positive" }
	}
}
