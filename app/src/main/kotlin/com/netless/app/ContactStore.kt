package com.netless.app

import com.netless.common.NodeId
import com.netless.common.ProfileId
import com.netless.content.DurableEncryptedContentStore
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.identity.Profile
import com.netless.protocol.ContactCodec
import com.netless.transport.DiscoveredNode
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContactRecord(val profileId: ProfileId, val nodeId: NodeId, val publicKey: PublicKey, val name: String, val endpoint: TransportEndpoint)

class ContactStore(private val store: DurableEncryptedContentStore? = null, private val verifySignature: (PublicKey, ByteArray, Signature) -> Boolean = { _, _, _ -> false }) {
	private val contactsByNode = LinkedHashMap<NodeId, DiscoveredNode>()
	private val _contacts = MutableStateFlow<List<DiscoveredNode>>(emptyList())
	val contacts: StateFlow<List<DiscoveredNode>> = _contacts.asStateFlow()

	init { store?.ids()?.filter { it.startsWith("discovered-contact:") }?.forEach { id -> store.get(id)?.let { runCatching { BleAdvertisementCodec.decode(it) }.getOrNull() }?.let(::restore) } }

	private fun restore(advertisement: com.netless.transport.DiscoveryAdvertisement) {
		val profile = advertisement.metadata["profile"]?.let { runCatching { ContactCodec.decode(it) }.getOrNull() } ?: return
		val nodeId = advertisement.metadata["nodeId"]?.let(::NodeId) ?: return
		val address = advertisement.metadata["endpointAddress"] ?: return
		runCatching { import(profile, nodeId, TransportEndpoint(nodeId, address, advertisement.metadata)) }
	}

	@Synchronized fun upsert(node: DiscoveredNode) {
		val encoded = node.endpoint.metadata["profile"] ?: error("discovery node requires a signed profile")
		import(ContactCodec.decode(encoded), node.nodeId, node.endpoint)
	}

	fun contact(profileId: String): DiscoveredNode? = contactsByNode.values.firstOrNull { it.endpoint.metadata["profileId"] == profileId }

	fun import(encoded: String): ContactRecord = import(ContactCodec.decode(encoded))

	fun import(profile: Profile): ContactRecord = import(profile, NodeId(profile.id.value), TransportEndpoint(NodeId(profile.id.value), profile.id.value))

	@Synchronized fun import(profile: Profile, nodeId: NodeId, endpoint: TransportEndpoint): ContactRecord {
		require(endpoint.nodeId == nodeId) { "endpoint nodeId mismatch" }
		val derived = ProfileId(MessageDigest.getInstance("SHA-256").digest(profile.publicKey.encoded).joinToString("") { "%02x".format(it) })
		require(profile.id == derived) { "ProfileId does not match public key" }
		require(verifySignature(profile.publicKey, profile.signedPayload(), profile.signature)) { "Profile signature is invalid" }
		val metadata = endpoint.metadata + mapOf("profileId" to profile.id.value, "displayName" to profile.name, "identityKey" to Base64.getEncoder().encodeToString(profile.publicKey.encoded), "profile" to ContactCodec.encode(profile))
		val trustedEndpoint = TransportEndpoint(nodeId, endpoint.address, metadata)
		val record = ContactRecord(profile.id, nodeId, profile.publicKey, profile.name, trustedEndpoint)
		store?.put("discovered-contact:${record.nodeId.value}", BleAdvertisementCodec.encode(com.netless.transport.DiscoveryAdvertisement(record.nodeId.value, 1, record.nodeId.value, emptySet(), emptySet(), trustedEndpoint.metadata)))
		contactsByNode[record.nodeId] = DiscoveredNode(record.nodeId, trustedEndpoint, TransportCapabilities(true, true, 1, true, true))
		_contacts.value = contactsByNode.values.toList()
		return record
	}

	@Synchronized fun remove(nodeId: NodeId) { contactsByNode.remove(nodeId); _contacts.value = contactsByNode.values.toList() }
}
