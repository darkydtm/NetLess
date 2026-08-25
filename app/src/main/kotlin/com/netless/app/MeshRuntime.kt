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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

class MeshRuntime(
	private val localNode: NodeId,
	private val transports: TransportRegistry,
	private val route: (NodeId, TransportPolicy) -> Route?,
	private val relayStore: RelayStore? = null,
	private val codec: VersionedPacketCodecContract = VersionedPacketCodec,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) {
	private val deliveries = MutableSharedFlow<DeliveryReceipt>(extraBufferCapacity = 16)

	suspend fun send(content: ContentEnvelope, destination: NodeId, policy: TransportPolicy): DeliveryReceipt {
		val now = nowMillis()
		val packetId = PacketId(UUID.randomUUID().toString())
		val selected = route(destination, policy) ?: return receipt(packetId, DeliveryState.Failed)
		val packet = PacketEnvelope(ForwardingEnvelope(packetId, destination, selected.hops.first().nextNodeId, 0, selected.hops.size.toLong(), com.netless.common.TrafficClass.Reliable, byteArrayOf(1)), content, createdAtEpochMillis = now, expiresAtEpochMillis = selected.expiresAtMillis)
		val bytes = codec.encode(packet, now)
		return forward(bytes, packetId, selected)
	}

	suspend fun receive(bytes: ByteArray, ingress: TransportType): DeliveryReceipt {
		val packet = codec.decode(bytes, nowMillis())
		val receipt = receipt(packet.forwarding.packetId, if (packet.forwarding.finalNodeId == localNode) DeliveryState.Delivered else DeliveryState.Relaying)
		if (receipt.state == DeliveryState.Delivered) deliveries.tryEmit(receipt)
		return receipt
	}

	fun observeDelivery(packetId: PacketId): Flow<DeliveryReceipt> = deliveries.asSharedFlow()

	private suspend fun forward(bytes: ByteArray, packetId: PacketId, route: Route): DeliveryReceipt {
		for (hop in route.hops.filter { transports.adapter(it.transport) != null }) {
			val adapter = transports.adapter(hop.transport) ?: continue
			try {
				val connection = adapter.connect(hop.endpoint)
				connection.send(bytes)
				connection.close()
			} catch (_: Exception) { continue }
		}
		val result = receipt(packetId, DeliveryState.Delivered)
		deliveries.tryEmit(result)
		return result
	}

	private fun receipt(packetId: PacketId, state: DeliveryState) = DeliveryReceipt(packetId, state, localNode, nowMillis())
}
