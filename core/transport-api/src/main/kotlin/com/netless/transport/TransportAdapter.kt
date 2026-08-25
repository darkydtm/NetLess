package com.netless.transport

import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import kotlinx.coroutines.flow.Flow

data class AuthenticatedConnectionRequest(
	val expectedPeerIdentity: PublicKey,
	val sessionId: String,
	val protocolVersion: Int = 1,
	val sign: suspend (ByteArray) -> Signature,
	val verify: suspend (PublicKey, ByteArray, Signature) -> Boolean,
)

interface TransportAdapter {
	val type: TransportType
	val availability: Flow<TransportState>

	suspend fun connect(endpoint: TransportEndpoint): TransportConnection

	suspend fun connectAuthenticated(endpoint: TransportEndpoint, request: AuthenticatedConnectionRequest): TransportConnection =
		throw UnsupportedOperationException("authenticated connections are not supported")

	fun supports(capability: DiscoveryCapability): Boolean
	fun fail() { }
}
