package com.netless.transport

import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.Signature as JavaSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SessionTransportTest {
	@Test
	fun peersAuthenticateAndDeriveTheSameSessionKey() = runBlocking {
		val first = identity()
		val second = identity()
		ServerSocket(0).use { server ->
			Socket("127.0.0.1", server.localPort).use { client ->
				server.accept().use { accepted ->
					val firstTransport = SessionTransport(client)
					val secondTransport = SessionTransport(accepted)
					coroutineScope {
						val firstKey = async(Dispatchers.IO) { firstTransport.establishAuthenticated(1, "session", first.public, first::sign, ::verify) }
						val secondKey = async(Dispatchers.IO) { secondTransport.establishAuthenticated(1, "session", second.public, second::sign, ::verify) }
						assertContentEquals(firstKey.await().encoded, secondKey.await().encoded)
					}
				}
			}
		}
	}

	private fun identity(): TestIdentity {
		val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
		return TestIdentity(PublicKey(pair.public.encoded), pair)
	}

	private suspend fun verify(public: PublicKey, data: ByteArray, signature: Signature): Boolean =
		JavaSignature.getInstance("SHA256withECDSA").run {
			initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.encoded)))
			update(data)
			verify(signature.bytes)
		}

	private data class TestIdentity(val public: PublicKey, private val pair: KeyPair) {
		 suspend fun sign(data: ByteArray): Signature = JavaSignature.getInstance("SHA256withECDSA").run {
			initSign(pair.private)
			update(data)
			Signature(sign())
		}
	}
}
