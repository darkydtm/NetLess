package com.netless.app

import android.app.Application
import com.netless.identity.IdentityRepository
import com.netless.identity.KeystoreIdentityRepository
import com.netless.transport.DiscoveryTransport
import com.netless.content.AesContentCipher
import com.netless.content.DurableEncryptedContentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportType
import java.util.UUID
import com.netless.database.RelayStore
import com.netless.network.RouteGraph
import com.netless.network.RouteEngine
import com.netless.network.RouteHop
import com.netless.network.RouteMetrics
import com.netless.transport.TransportPolicy

class NetlessApplication : Application() {
	lateinit var container: AppContainer
		private set

	override fun onCreate() {
		super.onCreate()
		container = AppContainer(this)
	}
}

class AppContainer(application: Application) {
	val identityRepository: IdentityRepository = KeystoreIdentityRepository(application)
	val contacts = ContactStore()
	val discoveryTransport: DiscoveryTransport = AndroidBleDiscoveryTransport(application)
	val wifiDirectDiscovery: DiscoveryTransport = WifiDirectDiscoveryTransport(application)
	val wifiDirect = com.netless.transport.WifiDirectDataTransport()
	private val localIdentity = identityRepository.getOrCreateIdentityBlocking()
	val transportRegistry = TransportRegistry().also { it.register(wifiDirect.asAdapter(localIdentity.publicKey, { data -> identityRepository.sign(data) }, { key, data, signature -> identityRepository.verify(key, data, signature) })) }
	val contentStore = DurableEncryptedContentStore(java.io.File(application.filesDir, "content.db"), AesContentCipher())
	val messages = MessageRepository(contentStore)
	val meshRuntime = MeshRuntime(
		com.netless.common.NodeId(localIdentity.profileId.value), transportRegistry,
		{ destination, policy ->
			val available = transportRegistry.availableAdapters()
			val hops = contacts.contacts.value.flatMap { node ->
				available.filter { adapter -> adapter.type.name == node.endpoint.metadata["transport"] || node.endpoint.metadata["transport"] == null }.map {
					RouteHop(com.netless.common.NodeId(localIdentity.profileId.value), node.nodeId, it.type, node.endpoint, RouteMetrics(1.0, 1.0, 1.0, 1.0), Long.MAX_VALUE)
				}
			}
			RouteEngine().select(destination, RouteGraph(hops), policy, System.currentTimeMillis())
		},
		RelayStore(storageFile = java.io.File(application.filesDir, "relay.db")),
		signPacket = { data -> identityRepository.sign(data).bytes },
		verifySenderSignature = { packet, data ->
			val content = packet.content
			val key = contacts.contacts.value.firstOrNull { it.nodeId.value == content.senderProfileId.value }?.endpoint?.metadata?.get("identityKey")
			key != null && identityRepository.verify(com.netless.crypto.PublicKey(java.util.Base64.getDecoder().decode(key)), data, com.netless.crypto.Signature(content.senderSignature))
		},
		onContent = { content -> messages.onContent(content) },
	)
	val peerMessages = PeerMessageRuntime({ bytes, ingress -> meshRuntime.receive(bytes, ingress) }, wifiDirect, wifiDirectDiscovery as WifiDirectDiscoveryTransport, localIdentity.publicKey,
		{ data -> identityRepository.sign(data) }, { key, data, signature -> identityRepository.verify(key, data, signature) },
		{ bytes, ingress -> meshRuntime.receiveFrame(bytes, ingress) })
	val audioRuntime = AudioRuntime()
	val runtimeController = RuntimeController(
		CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
		discoveryTransport,
		wifiDirectDiscovery,
		contacts,
		audioRuntime,
		peerMessages,
		meshRuntime,
		{ port ->
			val identity = runCatching { kotlinx.coroutines.runBlocking { identityRepository.getOrCreateIdentity() } }.getOrNull() ?: return@RuntimeController null
			DiscoveryAdvertisement(
				identity.profileId.value,
				1,
				UUID.randomUUID().toString(),
				setOf(DiscoveryCapability.Relay, DiscoveryCapability.AcceptIncoming),
				setOf(TransportType.WifiDirect),
				mapOf("port" to port.toString(), "identityKey" to java.util.Base64.getEncoder().encodeToString(identity.publicKey.encoded)),
			)
		},
	)
}

private fun com.netless.transport.WifiDirectDataTransport.asAdapter(identity: com.netless.crypto.PublicKey, sign: suspend (ByteArray) -> com.netless.crypto.Signature, verify: suspend (com.netless.crypto.PublicKey, ByteArray, com.netless.crypto.Signature) -> Boolean) = object : com.netless.transport.TransportAdapter {
	override val type = com.netless.transport.TransportType.WifiDirect
	override val availability = state
	override suspend fun connect(endpoint: com.netless.transport.TransportEndpoint) = this@asAdapter.connectAuthenticated(endpoint, 1, UUID.randomUUID().toString(), identity, sign, verify)
	override fun supports(capability: com.netless.transport.DiscoveryCapability) = capability == com.netless.transport.DiscoveryCapability.AcceptIncoming
	override fun fail() = this@asAdapter.markFailed()
}

private fun IdentityRepository.getOrCreateIdentityBlocking() = kotlinx.coroutines.runBlocking { getOrCreateIdentity() }
