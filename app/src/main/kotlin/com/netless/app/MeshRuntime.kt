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
import com.netless.protocol.ControlCodec
import com.netless.protocol.Acknowledgement
import com.netless.protocol.HopAcknowledgement
import com.netless.protocol.Receipt
import com.netless.transport.TransportConnection
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
	private val signPacket: suspend (ByteArray) -> ByteArray = { ByteArray(0) },
	private val verifySenderSignature: suspend (PacketEnvelope, ByteArray) -> Boolean = { _, _ -> false },
	private val onContent: suspend (ContentEnvelope) -> Unit = {},
	private val localIdentity: PublicKey? = null,
	private val localProfileId: com.netless.common.ProfileId? = null,
	private val signSession: (suspend (ByteArray) -> Signature)? = null,
	private val verifySession: (suspend (PublicKey, ByteArray, Signature) -> Boolean)? = null,
) {
	private val deliveries = mutableMapOf<PacketId, MutableStateFlow<DeliveryReceipt?>>()
	private val terminalReceipts = mutableMapOf<PacketId, DeliveryReceipt>()
	private val receiptIngress = mutableMapOf<PacketId, TransportType>()
	private val deliveryLock = Any()

	suspend fun send(content: ContentEnvelope, destination: NodeId, policy: TransportPolicy): DeliveryReceipt {
		val now = nowMillis()
		val packetId = PacketId(UUID.randomUUID().toString())
		val selected = route(destination, policy)
		if (selected == null) {
			return receipt(packetId, DeliveryState.Failed).also(::emit)
		}
		val firstHop = selected.hops.firstOrNull()?.nextNodeId
		val currentNode = firstHop ?: localNode
		val nextHop = selected.hops.getOrNull(1)?.nextNodeId ?: destination.takeUnless { it == currentNode }
		val unsigned = PacketEnvelope(ForwardingEnvelope(packetId, destination, nextHop, 0, selected.hops.size.toLong(), com.netless.common.TrafficClass.Reliable, ByteArray(32), currentNode), content.copy(senderSignature = byteArrayOf()), createdAtEpochMillis = now, expiresAtEpochMillis = selected.expiresAtMillis)
		val signature = signPacket(originCanonical(unsigned, now))
		if (signature.isEmpty()) return receipt(packetId, DeliveryState.Failed).also(::emit)
		val signed = unsigned.copy(content = content.copy(senderSignature = signature))
		val integrity = MessageDigest.getInstance("SHA-256").digest(canonical(signed, now))
		val bytes = codec.encode(signed.copy(forwarding = signed.forwarding.copy(perHopIntegrity = integrity)), now)
		relayStore?.put(bytes, packetId, selected.expiresAtMillis, selected.hops.firstOrNull()?.nextNodeId, destination)
		return forward(bytes, packetId, selected.hops.firstOrNull(), now)
	}

	suspend fun receive(bytes: ByteArray, ingress: TransportType): DeliveryReceipt {
		val now = nowMillis()
		val packet = try {
			codec.decode(bytes, now).also {
				require(localProfileId == null || localProfileId in it.content.recipients) { "content is not addressed to local profile" }
				require(it.forwarding.currentNodeId == localNode) { "packet is not addressed to this node" }
				require(validIntegrity(it, bytes, now)) { "packet integrity check failed" }
				require(verifySenderSignature(it, originCanonical(it, now))) { "packet signature check failed" }
			}
		} catch (error: Exception) {
			val packetId = runCatching { codec.decode(bytes, now).forwarding.packetId }.getOrNull()
			if (packetId != null) emit(receipt(packetId, DeliveryState.Failed))
			throw error
		}
		relayStore?.get(packet.forwarding.packetId)?.terminalReceipt?.let { return it.also(::emit) }
		if (terminalReceipts[packet.forwarding.packetId] != null) {
			return terminalReceipts.getValue(packet.forwarding.packetId).also(::emit)
		}
		val pending = relayStore?.get(packet.forwarding.packetId)
		receiptIngress.putIfAbsent(packet.forwarding.packetId, ingress)
		if (pending?.state == com.netless.database.RelayState.PENDING) {
			val retryHop = route(packet.forwarding.finalNodeId, TransportPolicy.Automatic())?.hops?.firstOrNull()
			return if (retryHop != null) forward(pending.packet, packet.forwarding.packetId, retryHop, now) else receipt(packet.forwarding.packetId, DeliveryState.Relaying).also(::emit)
		}
		relayStore?.put(bytes, packet.forwarding.packetId, packet.expiresAtEpochMillis, packet.forwarding.nextHop, packet.forwarding.finalNodeId)
		if (packet.forwarding.finalNodeId == localNode) {
			val result = try {
				onContent(packet.content)
				relayStore?.put(bytes, packet.forwarding.packetId, packet.expiresAtEpochMillis, null)
				DeliveryReceipt(packet.forwarding.packetId, DeliveryState.Delivered, packet.forwarding.finalNodeId, now)
			} catch (error: Exception) {
				receipt(packet.forwarding.packetId, DeliveryState.Failed)
			}
			emit(result)
			if (result.state == DeliveryState.Delivered) {
				terminalReceipts[result.packetId] = result
				relayStore?.markTerminal(result.packetId, result)
			}
			return result
		}
		val selected = route(packet.forwarding.finalNodeId, TransportPolicy.Automatic())
			?: return receipt(packet.forwarding.packetId, DeliveryState.Failed).also(::emit)
		val hop = selected.hops.firstOrNull() ?: return receipt(packet.forwarding.packetId, DeliveryState.Failed).also(::emit)
		val followingHop = selected.hops.getOrNull(1)?.nextNodeId ?: packet.forwarding.finalNodeId.takeUnless { it == hop.nextNodeId }
		val rewritten = packet.copy(forwarding = packet.forwarding.copy(currentNodeId = hop.nextNodeId, nextHop = followingHop, hopCount = packet.forwarding.hopCount + 1, perHopIntegrity = byteArrayOf(0)))
		val integrity = MessageDigest.getInstance("SHA-256").digest(canonical(rewritten, now))
		val forwardedBytes = codec.encode(rewritten.copy(forwarding = rewritten.forwarding.copy(perHopIntegrity = integrity)), now)
		relayStore?.put(forwardedBytes, packet.forwarding.packetId, packet.expiresAtEpochMillis, hop.nextNodeId)
		return forward(forwardedBytes, packet.forwarding.packetId, hop, now)
	}

	suspend fun receiveFrame(frame: ByteArray, ingress: TransportType): ByteArray {
		return when (val decoded = ControlCodec.decode(frame)) {
			is com.netless.protocol.Forward -> {
				val receipt = receive(decoded.packet, ingress)
				if (receipt.state != DeliveryState.Relaying) ControlCodec.receipt(receipt) else ControlCodec.acknowledgement(HopAcknowledgement(receipt.packetId, localNode, true))
			}
			is Acknowledgement -> frame
			is Receipt -> {
				require(decoded.value.state == DeliveryState.Delivered || decoded.value.state == DeliveryState.Failed) { "non-terminal receipt" }
				receiptIngress[decoded.value.packetId]?.let { expected -> require(expected == ingress) { "receipt ingress does not match delivery route" } }
				require(decoded.value.timestampEpochMillis <= nowMillis()) { "receipt timestamp is in the future" }
				val stored = relayStore?.get(decoded.value.packetId) ?: error("receipt is not admitted")
				require(stored.state == com.netless.database.RelayState.PENDING && stored.nextHop != null) { "receipt is not admitted" }
				val packet = codec.decode(stored.packet, nowMillis())
				require(packet.forwarding.packetId == decoded.value.packetId) { "receipt packet id does not match packet" }
				require(decoded.value.nodeId == packet.forwarding.finalNodeId) { "receipt destination does not match packet" }
				val result = decoded.value
				terminalReceipts[result.packetId] = result
				if (result.state == DeliveryState.Delivered) relayStore?.markTerminal(result.packetId, result)
				emit(result)
				ControlCodec.receipt(result)
			}
		}
	}

	fun observeDelivery(packetId: PacketId): Flow<DeliveryReceipt> = synchronized(deliveryLock) {
		deliveries.getOrPut(packetId) { MutableStateFlow(null) }
	}.asStateFlow().filter { it != null }.let { flow -> kotlinx.coroutines.flow.flow { flow.collect { emit(it!!) } } }

	private suspend fun forward(bytes: ByteArray, packetId: PacketId, hop: com.netless.network.RouteHop?, now: Long = nowMillis()): DeliveryReceipt {
		if (hop == null) return receipt(packetId, DeliveryState.Failed).also(::emit)
		require(hop.endpoint.nodeId == hop.nextNodeId) { "endpoint node identity does not match route hop" }
		require(hop.endpoint.metadata["nodeId"] == hop.nextNodeId.value) { "missing or inconsistent endpoint node identity" }
		require(!hop.endpoint.metadata["identityKey"].isNullOrBlank()) { "missing endpoint identity key" }
		val adapter = transports.available(hop.transport)
			?: return receipt(packetId, DeliveryState.Failed).also(::emit)
		var propagatedReceipt: DeliveryReceipt? = null
		try {
				val expectedKey = com.netless.crypto.PublicKey(java.util.Base64.getDecoder().decode(hop.endpoint.metadata.getValue("identityKey")))
				require(localIdentity != null && signSession != null && verifySession != null) { "authenticated transport configuration is required" }
				val connection = adapter.connectAuthenticated(hop.endpoint, com.netless.transport.AuthenticatedConnectionRequest(expectedKey, hop.endpoint.metadata["sessionId"] ?: UUID.randomUUID().toString(), 1, signSession!!, verifySession!!))
				require(connection.peerIdentity == expectedKey) { "authenticated peer identity does not match route hop" }
				coroutineScope {
					val incoming = Channel<ByteArray>(Channel.UNLIMITED)
					val collector = launch { connection.incomingPackets.collect { incoming.send(it) } }
					receiptIngress[packetId] = hop.transport
					connection.send(ControlCodec.forward(bytes))
					when (val response = ControlCodec.decode(incoming.receive())) {
				is Receipt -> {
					require(response.value.packetId == packetId && response.value.nodeId == codec.decode(bytes, now).forwarding.finalNodeId)
					require(response.value.timestampEpochMillis <= now) { "receipt timestamp is in the future" }
					if (response.value.state == DeliveryState.Failed) {
						propagatedReceipt = response.value
					} else {
						require(response.value.state == DeliveryState.Delivered)
					require(response.value.nodeId == codec.decode(bytes, now).forwarding.finalNodeId) { "receipt destination does not match packet" }
					propagatedReceipt = response.value
						if (relayStore?.get(packetId)?.state == com.netless.database.RelayState.PENDING) {
							relayStore.markTerminal(packetId, response.value)
						}
					}
				}
				is Acknowledgement -> {
					require(response.value.packetId == packetId && response.value.nodeId == hop.nextNodeId && response.value.accepted)
					if (response.value.finalDelivery) {
						require(hop.nextNodeId == codec.decode(bytes, now).forwarding.finalNodeId) { "terminal acknowledgement from non-final hop" }
						propagatedReceipt = DeliveryReceipt(packetId, DeliveryState.Delivered, hop.nextNodeId, now)
						if (relayStore?.get(packetId)?.state == com.netless.database.RelayState.PENDING) {
							relayStore.markTerminal(packetId, propagatedReceipt!!)
						}
					} else {
						val receipt = ControlCodec.decode(incoming.receive())
						require(receipt is Receipt && receipt.value.packetId == packetId && receipt.value.nodeId == codec.decode(bytes, now).forwarding.finalNodeId && receipt.value.state == DeliveryState.Delivered)
						propagatedReceipt = receipt.value
						if (relayStore?.get(packetId)?.state == com.netless.database.RelayState.PENDING) {
							relayStore.markTerminal(packetId, propagatedReceipt!!)
						}
					}
				}
				else -> error("unexpected control response")
					}
					collector.cancel()
				}
				connection.close()
			} catch (error: Exception) {
				try { adapter.fail() } catch (_: Exception) { }
				return receipt(packetId, DeliveryState.Failed).also(::emit)
			}
		val result = propagatedReceipt ?: receipt(packetId, DeliveryState.Relaying)
		if (result.state == DeliveryState.Delivered) terminalReceipts[packetId] = result
		if (result.state == DeliveryState.Delivered) relayStore?.markDelivered(packetId)
		emit(result)
		return result
	}

	private fun emit(receipt: DeliveryReceipt) = synchronized(deliveryLock) {
		deliveries.getOrPut(receipt.packetId) { MutableStateFlow(null) }.value = receipt
	}

	private fun validIntegrity(packet: PacketEnvelope, bytes: ByteArray, now: Long): Boolean {
		val supplied = packet.forwarding.perHopIntegrity
		val expected = MessageDigest.getInstance("SHA-256").digest(canonical(packet, now))
		return supplied.contentEquals(expected)
	}

	private fun canonical(packet: PacketEnvelope, now: Long): ByteArray = codec.encode(packet.copy(
		forwarding = packet.forwarding.copy(perHopIntegrity = byteArrayOf(0)),
		content = packet.content.copy(senderSignature = byteArrayOf(0)),
	), now)

	private fun originCanonical(packet: PacketEnvelope, now: Long): ByteArray = codec.encode(packet.copy(
		forwarding = packet.forwarding.copy(currentNodeId = packet.forwarding.finalNodeId, nextHop = null, hopCount = 0, perHopIntegrity = byteArrayOf(0)),
		content = packet.content.copy(senderSignature = byteArrayOf(0)),
	), now)

	private fun receipt(packetId: PacketId, state: DeliveryState) = DeliveryReceipt(packetId, state, localNode, nowMillis())
}
