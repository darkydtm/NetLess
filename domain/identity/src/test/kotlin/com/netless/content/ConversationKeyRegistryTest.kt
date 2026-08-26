package com.netless.content

import com.netless.crypto.AuthenticatedKeyExchange
import com.netless.crypto.EphemeralKeyExchange
import com.netless.crypto.ExchangeSigner
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.security.KeyPairGenerator
import java.security.Signature as JcaSignature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ConversationKeyRegistryTest {
	@Test
	fun rejectsReplayedSessionOffers() {
		val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
		val identity = PublicKey(pair.public.encoded)
		val signer = ExchangeSigner { data -> JcaSignature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); Signature(sign()) } }
		val exchange = AuthenticatedKeyExchange { public, data, signature -> JcaSignature.getInstance("SHA256withECDSA").run { initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public.encoded))); update(data); verify(signature.bytes) } }
		val remote = EphemeralKeyExchange.generate()
		val local = EphemeralKeyExchange.generate()
		val offer = exchange.createOffer("session", remote.publicKey, identity, signer)
		val registry = ConversationKeyRegistry { _: PublicKey, _: ByteArray, _: Signature -> exchange.verifyOffer(offer) }
		registry.establish(offer, local, remote.publicKey)

		assertFailsWith<IllegalArgumentException> { registry.establish(offer, local, remote.publicKey) }
	}
}
