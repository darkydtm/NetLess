package com.netless.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals

class SessionKeyExchangeTest {
	@Test
	fun bothPeersDeriveTheSameKey() {
		val first = EphemeralKeyExchange.generate()
		val second = EphemeralKeyExchange.generate()
		val transcript = EphemeralKeyExchange.transcript(first.publicKey, second.publicKey, "session")

		assertContentEquals(
			first.derive(second.publicKey, transcript).encoded,
			second.derive(first.publicKey, transcript).encoded,
		)
	}
}
