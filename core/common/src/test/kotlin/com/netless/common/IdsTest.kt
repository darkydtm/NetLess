package com.netless.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdsTest {
	@Test
	fun rejectsEmptyIds() {
		assertFailsWith<IllegalArgumentException> { NodeId("") }
		assertFailsWith<IllegalArgumentException> { PacketId(" ") }
		assertFailsWith<IllegalArgumentException> { ProfileId("") }
	}

	@Test
	fun usesBalancedTransferDefaults() {
		assertEquals(
			TransferPolicy(
				mode = TransferMode.Balanced,
				maxHops = 2,
				ttlSeconds = 86_400,
				relayEnabled = true,
			),
			TransferPolicy(),
		)
	}
}
