package com.netless.app

import android.content.Intent
import android.provider.Settings

fun appLocaleSettingsIntent(packageName: String): Intent =
	Intent(Settings.ACTION_APP_LOCALE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
