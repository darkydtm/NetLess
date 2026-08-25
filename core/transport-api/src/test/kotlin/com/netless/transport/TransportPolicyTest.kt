package com.netless.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportPolicyTest {
	@Test
	fun `preferred policy keeps ordered preferences`() {
		val policy = TransportPolicy.Preferred(listOf(TransportType.WifiDirect, TransportType.Bluetooth))

		assertEquals(listOf(TransportType.WifiDirect, TransportType.Bluetooth), policy.preferences)
	}

	@Test
	fun `strict policy exposes only selected transport`() {
		val policy = TransportPolicy.Strict(TransportType.Bluetooth)

		assertEquals(listOf(TransportType.Bluetooth), policy.preferences)
		assertEquals(TransportType.Bluetooth, policy.strictTransport)
		assertTrue(policy.isStrict)
	}

	@Test
	fun `strict policy cannot be created without a transport`() {
		assertFailsWith<IllegalArgumentException> { TransportPolicy.Strict(null) }
	}

	@Test
	fun `preferred policy cannot be created without preferences`() {
		assertFailsWith<IllegalArgumentException> { TransportPolicy.Preferred(emptyList()) }
	}
}
