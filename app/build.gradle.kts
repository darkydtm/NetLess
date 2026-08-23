plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "com.netless.app"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.netless.app"
		minSdk = 26
		targetSdk = 35
		versionCode = 1
		versionName = "0.1"
	}
}
