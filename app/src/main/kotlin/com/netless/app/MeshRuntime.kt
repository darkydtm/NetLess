package com.netless.app

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.database.RelayStore
import com.netless.network.Route
import com.netless.protocol.ContentEnvelope
import com.netless.protocol.DeliveryReceipt
import com.netless.protocol.DeliveryState
import com.netless.protocol.ForwardingEnvelope
import com.netless.protocol.PacketEnvelope
import com.netless.protocol.VersionedPacketCodecContract
import com.netless.protocol.VersionedPacketCodec
import com.netless.transport.TransportConnection
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.security.MessageDigest
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature

class MeshRuntime(
	private val localNode: NodeId,
	private val transports: TransportRegistry,
	private val route: suspend (NodeId, TransportPolicy) -> Route?,
	private val relayStore: RelayStore? = null,
	private val codec: VersionedPacketCodecContract = VersionedPacketCodec,
	private val nowMillis: () -> Long = System::currentTimeMillis,
	private val signPacket: suspend (ByteArray) -> ByteArray = { byteArrayOf() },
	private val verifySenderSignature: suspend (PacketEnvelope, ByteArray) -> Boolean = { packet, _ -> packet.content.senderSignature.isNotEmpty() },
	private val onContent: suspend (ContentEnvelope) -> Unit = {},
) {
	private val deliveries = mutableMapOf<PacketId, MutableStateFlow<DeliveryReceipt?>>()
	private val deliveryLock = Any()

	suspend fun send(content: ContentEnvelope, destination: NodeId, policy: TransportPolicy): DeliveryReceipt {
		val now = nowMillis()
		val packetId = PacketId(UUID.randomUUID().toString())
		val selected = route(destination, policy)
		if (selected == null) {
			return receipt(packetId, DeliveryState.Failed).also(::emit)
		}
		val unsigned = PacketEnvelope(ForwardingEnvelope(packetId, localNode, destination, selected.hops.firstOrNull()?.nextNodeId, 0, selected.hops.size.toLong(), com.netless.common.TrafficClass.Reliable, byteArrayOf(0)), content.copy(senderSignature = byteArrayOf(0)), createdAtEpochMillis = now, expiresAtEpochMillis = selected.expiresAtMillis)
		val signature = signPacket(canonical(unsigned))
		if (signature.isEmpty()) return receipt(packetId, DeliveryState.Failed)
		val signed = unsigned.copy(content = content.copy(senderSignature = signature))
		val integrityInput = signed.copy(
			forwarding = signed.forwarding.copy(perHopIntegrity = byteArrayOf(0)),
			content = signed.content.copy(senderSignature = byteArrayOf(0)),
		)
		val bytes = codec.encode(signed.copy(forwarding = signed.forwarding.copy(perHopIntegrity = MessageDigest.getInstance("SHA-256").digest(codec.encode(integrityInput, now)))), now)
		relayStore?.put(bytes, packetId, selected.expiresAtMillis, selected.hops.firstOrNull()?.nextNodeId)
		return forward(bytes, packetId, selected.hops.firstOrNull())
	}

		suspend fun receive(bytes: ByteArray, ingress: TransportType): DeliveryReceipt {
		val now = nowMillis()
		val packet = codec.decode(bytes, now)
		require(packet.forwarding.currentNodeId == localNode && (packet.forwarding.nextHop == null || packet.forwarding.nextHop == localNode)) { "packet is not addressed to this node" }
		require(validIntegrity(packet, bytes)) { "packet integrity check failed" }
		require(verifySenderSignature(packet, canonical(packet))) { "packet signature check failed" }
		if (relayStore?.contains(packet.forwarding.packetId) == true)
			return receipt(packet.forwarding.packetId, DeliveryState.Relaying)
		relayStore?.put(bytes, packet.forwarding.packetId, packet.expiresAtEpochMillis, packet.forwarding.nextHop)
		if (packet.forwarding.finalNodeId == localNode) {
			onContent(packet.content)
			relayStore?.put(bytes, packet.forwarding.packetId, packet.expiresAtEpochMillis, null)
			relayStore?.markDelivered(packet.forwarding.packetId)
			val result = receipt(packet.forwarding.packetId, DeliveryState.Delivered)
			emit(result)
			return result
		}
		val selected = route(packet.forwarding.finalNodeId, TransportPolicy.Automatic())
			?: return receipt(packet.forwarding.packetId, DeliveryState.Failed).also(::emit)
		val hop = selected.hops.firstOrNull() ?: return receipt(packet.forwarding.packetId, DeliveryState.Failed).also(::emit)
		val rewritten = packet.copy(forwarding = packet.forwarding.copy(currentNodeId = hop.nextNodeId, nextHop = hop.nextNodeId, hopCount = packet.forwarding.hopCount + 1, perHopIntegrity = byteArrayOf(0)))
		val integrity = MessageDigest.getInstance("SHA-256").digest(codec.encode(rewritten, now))
		val forwardedBytes = codec.encode(rewritten.copy(forwarding = rewritten.forwarding.copy(perHopIntegrity = integrity)), now)
		relayStore?.put(forwardedBytes, packet.forwarding.packetId, packet.expiresAtEpochMillis, hop.nextNodeId)
		return forward(forwardedBytes, packet.forwarding.packetId, hop)
	}

	fun observeDelivery(packetId: PacketId): Flow<DeliveryReceipt> = synchronized(deliveryLock) {
		deliveries.getOrPut(packetId) { MutableStateFlow(null) }
	}.asStateFlow().filter { it != null }.let { flow -> kotlinx.coroutines.flow.flow { flow.collect { emit(it!!) } } }

	private suspend fun forward(bytes: ByteArray, packetId: PacketId, hop: com.netless.network.RouteHop?): DeliveryReceipt {
		if (hop == null) return receipt(packetId, DeliveryState.Failed).also(::emit)
		val adapter = transports.available(hop.transport)
			?: return receipt(packetId, DeliveryState.Failed).also(::emit)
			try {
				val connection = adapter.connect(hop.endpoint)
				connection.send(bytes)
				connection.close()
			} catch (error: Exception) {
				return receipt(packetId, DeliveryState.Failed).also(::emit)
			}
		val result = receipt(packetId, DeliveryState.Relaying)
		emit(result)
		return result
	}

	private fun emit(receipt: DeliveryReceipt) = synchronized(deliveryLock) {
		deliveries.getOrPut(receipt.packetId) { MutableStateFlow(null) }.value = receipt
	}

	private fun validIntegrity(packet: PacketEnvelope, bytes: ByteArray): Boolean {
		val supplied = packet.forwarding.perHopIntegrity
		val blank = packet.copy(
			forwarding = packet.forwarding.copy(perHopIntegrity = byteArrayOf(0)),
			content = packet.content.copy(senderSignature = byteArrayOf(0)),
		)
		val expected = MessageDigest.getInstance("SHA-256").digest(codec.encode(blank, packet.createdAtEpochMillis))
		return supplied.contentEquals(expected)
	}

	private fun canonical(packet: PacketEnvelope): ByteArray = codec.encode(packet.copy(
		forwarding = packet.forwarding.copy(perHopIntegrity = byteArrayOf(0)),
		content = packet.content.copy(senderSignature = byteArrayOf(0)),
	), packet.createdAtEpochMillis)

	private fun receipt(packetId: PacketId, state: DeliveryState) = DeliveryReceipt(packetId, state, localNode, nowMillis())
}
