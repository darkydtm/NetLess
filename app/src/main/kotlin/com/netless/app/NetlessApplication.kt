package com.netless.app

import android.app.Application
import com.netless.identity.IdentityRepository
import com.netless.identity.KeystoreIdentityRepository
import com.netless.transport.DiscoveryTransport
import com.netless.content.AesContentCipher
import com.netless.content.EncryptedContentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
	val contentStore = EncryptedContentStore(AesContentCipher())
	val audioRuntime = AudioRuntime()
	val runtimeController = RuntimeController(
		CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
		discoveryTransport,
		wifiDirectDiscovery,
		contacts,
		audioRuntime,
	)
}
