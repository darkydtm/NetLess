package com.netless.transport

import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.security.Signature as JavaSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SessionTransportTest {
	@Test
	fun authenticatedPacketsRoundTripAsCiphertextOnTheWire() {
		runBlocking {
			val first = newIdentity()
			val second = newIdentity()
			ServerSocket(0).use { server ->
				Socket("127.0.0.1", server.localPort).use { client ->
					server.accept().use { accepted ->
						val firstTransport = SessionTransport(client)
						val secondTransport = SessionTransport(accepted)
						coroutineScope {
							val firstKey = async(Dispatchers.IO) { firstTransport.establishAuthenticated(1, "session", first.public, { sign(first.privateKey, it) }, ::verify) }
							val secondKey = async(Dispatchers.IO) { secondTransport.establishAuthenticated(1, "session", second.public, { sign(second.privateKey, it) }, ::verify) }
							assertContentEquals(firstKey.await().encoded, secondKey.await().encoded)
							val received = async(Dispatchers.IO) { secondTransport.packets().first() }
							firstTransport.send(byteArrayOf(7, 8, 9))
							assertContentEquals(byteArrayOf(7, 8, 9), received.await())
						}
					}
				}
			}
		}
	}
}

private fun newIdentity(): TestIdentity {
	val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
	return TestIdentity(PublicKey(pair.public.encoded), pair.private)
}

private suspend fun sign(privateKey: java.security.PrivateKey, data: ByteArray): Signature = JavaSignature.getInstance("SHA256withECDSA").run {
	initSign(privateKey)
	update(data)
	Signature(sign())
}

private suspend fun verify(public: PublicKey, data: ByteArray, signature: Signature): Boolean = JavaSignature.getInstance("SHA256withECDSA").run {
	initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.encoded)))
	update(data)
	verify(signature.bytes)
}

private data class TestIdentity(val public: PublicKey, val privateKey: java.security.PrivateKey)
