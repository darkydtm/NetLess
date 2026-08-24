package com.netless.app

import com.netless.common.NodeId
import com.netless.transport.DiscoveredNode
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object BleAdvertisementCodec {
	private const val VERSION = 1

	fun encode(advertisement: DiscoveryAdvertisement): ByteArray = ByteArrayOutputStream().also { output ->
		DataOutputStream(output).use { data ->
			data.writeByte(VERSION)
			data.writeUTF(advertisement.discoveryHash)
			data.writeInt(advertisement.protocolVersion)
			data.writeUTF(advertisement.sessionId)
			data.writeInt(advertisement.capabilities.fold(0) { mask, capability -> mask or (1 shl capability.ordinal) })
			data.writeInt(advertisement.transportHints.fold(0) { mask, type -> mask or (1 shl type.ordinal) })
		}
	}.toByteArray()

	fun decode(bytes: ByteArray): DiscoveryAdvertisement = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
		require(data.readUnsignedByte() == VERSION) { "Unsupported BLE advertisement version" }
		val discoveryHash = data.readUTF()
		val protocolVersion = data.readInt()
		val sessionId = data.readUTF()
		val capabilityMask = data.readInt()
		val transportMask = data.readInt()
		val capabilities = DiscoveryCapability.values().filter { capabilityMask and (1 shl it.ordinal) != 0 }
		val transportHints = TransportType.values().filter { transportMask and (1 shl it.ordinal) != 0 }
		DiscoveryAdvertisement(discoveryHash, protocolVersion, sessionId, capabilities.toSet(), transportHints.toSet())
	}
}

class ContactStore {
	private val contactsByNode = LinkedHashMap<NodeId, DiscoveredNode>()
	private val _contacts = MutableStateFlow<List<DiscoveredNode>>(emptyList())
	val contacts: Flow<List<DiscoveredNode>> = _contacts.asStateFlow()

	@Synchronized
	fun upsert(node: DiscoveredNode) {
		contactsByNode[node.nodeId] = node
		_contacts.value = contactsByNode.values.toList()
	}

	@Synchronized
	fun remove(nodeId: NodeId) {
		contactsByNode.remove(nodeId)
		_contacts.value = contactsByNode.values.toList()
	}
}
