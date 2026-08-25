package com.netless.app

import android.app.Application
import com.netless.identity.IdentityRepository
import com.netless.identity.KeystoreIdentityRepository
import com.netless.transport.DiscoveryTransport
import com.netless.content.AesContentCipher
import com.netless.content.EncryptedContentStore
import com.netless.content.DurableEncryptedContentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportType
import java.util.UUID

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
	val contentStore = DurableEncryptedContentStore(java.io.File(application.filesDir, "content.db"), AesContentCipher())
	val messages = MessageRepository(contentStore)
	val peerMessages = PeerMessageRuntime(identityRepository, messages, wifiDirect, wifiDirectDiscovery as WifiDirectDiscoveryTransport)
	val audioRuntime = AudioRuntime()
	val runtimeController = RuntimeController(
		CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
		discoveryTransport,
		wifiDirectDiscovery,
		contacts,
		audioRuntime,
		peerMessages,
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
