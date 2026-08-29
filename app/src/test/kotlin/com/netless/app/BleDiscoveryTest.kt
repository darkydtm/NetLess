package com.netless.app

import com.netless.common.NodeId
import com.netless.transport.DiscoveredNode
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.netless.common.ProfileId
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.identity.Profile
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature as JcaSignature
import kotlin.test.assertFailsWith

class BleDiscoveryTest {
	@Test
	fun advertisementRoundTripPreservesDiscoveryData() {
		val source = DiscoveryAdvertisement(
			discoveryHash = "node-hash",
			protocolVersion = 1,
			sessionId = "session",
			capabilities = setOf(DiscoveryCapability.Relay, DiscoveryCapability.Advertise),
			transportHints = setOf(TransportType.Bluetooth),
		)

		assertEquals(source, BleAdvertisementCodec.decode(BleAdvertisementCodec.encode(source)))
	}

	@Test
	fun contactStoreUpsertsDiscoveredNodes() = runTest {
		val profile = signedProfile()
		val store = ContactStore(verifySignature = ::verify)
		val node = DiscoveredNode(
			NodeId("node"),
			TransportEndpoint(NodeId("node"), "AA:BB", mapOf("profile" to com.netless.protocol.ContactCodec.encode(profile))),
			TransportCapabilities(true, true, 1, true, false),
		)

		store.upsert(node)
		store.upsert(node.copy(endpoint = TransportEndpoint(NodeId("node"), "CC:DD", mapOf("profile" to com.netless.protocol.ContactCodec.encode(profile)))))

		assertEquals("CC:DD", store.contacts.first().single().endpoint.address)
	}

	@Test fun metadataOnlyDiscoveryIsRejected() {
		val nodeId = NodeId("node")
		assertFailsWith<IllegalArgumentException> { ContactStore().upsert(DiscoveredNode(nodeId, TransportEndpoint(nodeId, "AA:BB", mapOf("profileId" to "untrusted")), TransportCapabilities(true, true, 1, true, false))) }
	}

	private val keys = KeyPairGenerator.getInstance("EC").generateKeyPair()
	private fun signedProfile(): Profile {
		val key = PublicKey(keys.public.encoded)
		val id = ProfileId(MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) })
		val unsigned = Profile(id, key, "Alice", "Bio", 1, Signature(byteArrayOf(1)))
		return unsigned.copy(signature = Signature(JcaSignature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(unsigned.signedPayload()); sign() }))
	}
	private fun verify(key: PublicKey, data: ByteArray, signature: Signature) = JcaSignature.getInstance("SHA256withECDSA").run { initVerify(java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(key.encoded))); update(data); verify(signature.bytes) }
}
