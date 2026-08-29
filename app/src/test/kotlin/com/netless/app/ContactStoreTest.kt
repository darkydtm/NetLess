package com.netless.app

import com.netless.common.ProfileId
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.identity.Profile
import com.netless.content.DurableEncryptedContentStore
import com.netless.content.ContentCipher
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import com.netless.common.NodeId
import com.netless.transport.DiscoveredNode
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint

class ContactStoreTest {
	@Test fun signedImportPersistsAndRestores() {
		val file = File.createTempFile("contacts", ".db")
		val profile = profile()
		val store = ContactStore(DurableEncryptedContentStore(file, PlainCipher)) { _, _, _ -> true }
		assertEquals(profile.id, store.import(profile).profileId)
		assertEquals(profile.id.value, ContactStore(DurableEncryptedContentStore(file, PlainCipher)) { _, _, _ -> true }.contacts.value.single().endpoint.metadata["profileId"])
	}

	@Test fun rejectsDerivedIdMismatch() { assertFailsWith<IllegalArgumentException> { ContactStore().import(profile().copy(id = ProfileId("wrong"))) } }
	@Test fun rejectsMalformedData() { assertFailsWith<IllegalArgumentException> { ContactStore().import("not-contact") } }
	@Test fun rejectsTamperedSignatureWithoutPersistence() {
		val file = File.createTempFile("contacts", ".db")
		val store = ContactStore(DurableEncryptedContentStore(file, PlainCipher)) { _, _, _ -> false }
		assertFailsWith<IllegalArgumentException> { store.import(profile()) }
		assertEquals(emptyList(), store.contacts.value)
		assertEquals(emptyList(), DurableEncryptedContentStore(file, PlainCipher).ids())
	}
	@Test fun rejectsMetadataOnlyDiscovery() {
		val store = ContactStore()
		assertFailsWith<IllegalArgumentException> { store.upsert(DiscoveredNode(NodeId("node"), TransportEndpoint(NodeId("node"), "address", mapOf("profileId" to "id")), TransportCapabilities(true, true, 1, true, true))) }
	}
	@Test fun acceptsSignedDiscoveryAndPreservesEndpoint() {
		val profile = profile()
		val nodeId = NodeId("relay")
		val endpoint = TransportEndpoint(nodeId, "AA:BB", mapOf("profile" to com.netless.protocol.ContactCodec.encode(profile)))
		val store = ContactStore { _, _, _ -> true }
		store.upsert(DiscoveredNode(nodeId, endpoint, TransportCapabilities(true, true, 1, true, true)))
		assertEquals("AA:BB", store.contacts.value.single().endpoint.address)
	}
	@Test fun rejectsTamperedDiscoveryProfile() {
		val profile = profile()
		val node = DiscoveredNode(NodeId("relay"), TransportEndpoint(NodeId("relay"), "AA:BB", mapOf("profile" to com.netless.protocol.ContactCodec.encode(profile))), TransportCapabilities(true, true, 1, true, true))
		assertFailsWith<IllegalArgumentException> { ContactStore { _, _, _ -> false }.upsert(node) }
	}
	@Test fun rejectsOversizedProfileAdvertisement() {
		val advertisement = com.netless.transport.DiscoveryAdvertisement("node", 1, "session", emptySet(), emptySet(), mapOf("profile" to "x".repeat(9000)))
		assertFailsWith<IllegalArgumentException> { BleAdvertisementCodec.encode(advertisement) }
	}
	@Test fun persistenceFailureDoesNotPublishContact() {
		val directory = File.createTempFile("failing", ".db").also { it.delete(); it.mkdir() }
		val store = ContactStore(DurableEncryptedContentStore(File(directory, "contacts.db"), PlainCipher)) { _, _, _ -> true }
		assertFailsWith<IllegalStateException> { store.import(profile()) }
		assertEquals(emptyList(), store.contacts.value)
	}

	private fun profile(): Profile {
		val key = PublicKey("public-key".encodeToByteArray())
		val id = ProfileId(MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) })
		return Profile(id, key, "Alice", "", 1, Signature(byteArrayOf(1)))
	}

	private object PlainCipher : ContentCipher {
		override fun encrypt(content: ByteArray) = content
		override fun decrypt(content: ByteArray) = content
	}
}
