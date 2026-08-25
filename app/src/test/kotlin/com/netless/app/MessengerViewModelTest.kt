package com.netless.app

import com.netless.protocol.DeliveryState
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MessengerViewModelTest {
	@Test
	fun `strict mode requires confirmation`() = runTest {
		val viewModel = MessengerViewModel()

		viewModel.setPolicy(TransportPolicy.Strict(TransportType.Bluetooth))

		assertTrue(viewModel.uiState.value.strictWarningVisible)
		assertFalse(viewModel.uiState.value.networkPolicy.isStrict)
		viewModel.confirmStrictMode()
		assertTrue(viewModel.uiState.value.networkPolicy.isStrict)
	}

	@Test
	fun `delivery state is presented as simple label by default`() = runTest {
		val viewModel = MessengerViewModel()

		viewModel.onDelivery(DeliveryState.Relaying)

		assertEquals("Relayed", viewModel.uiState.value.deliveryLabel)
	}
}
