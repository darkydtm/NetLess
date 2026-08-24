package com.netless.crypto

import java.security.KeyPairGenerator
import java.security.Signature as JcaSignature
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthenticatedKeyExchangeTest {
	@Test
	fun verifiesSignedEphemeralOffer() {
		val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
		val identity = PublicKey(pair.public.encoded)
		val signer = ExchangeSigner { data ->
			JcaSignature.getInstance("SHA256withECDSA").run {
				initSign(pair.private)
				update(data)
				Signature(sign())
			}
		}
		val exchange = AuthenticatedKeyExchange { public, data, signature ->
			JcaSignature.getInstance("SHA256withECDSA").run {
				initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.encoded)))
				update(data)
				verify(signature.bytes)
			}
		}
		val offer = exchange.createOffer("session", byteArrayOf(1, 2), identity, signer)

		assertTrue(exchange.verifyOffer(offer))
		assertFalse(exchange.verifyOffer(offer.copy(sessionId = "replayed")))
	}
}
