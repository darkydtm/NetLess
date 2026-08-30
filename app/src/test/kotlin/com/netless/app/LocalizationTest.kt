package com.netless.app

import android.provider.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalizationTest {
	@Test
	fun `locale settings intent targets this package`() {
		val intent = appLocaleSettingsIntent("com.netless.app")

		assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
		assertEquals("com.netless.app", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
	}
}
