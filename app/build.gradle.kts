plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":domain:identity"))
	implementation(project(":data:identity"))
	implementation(project(":core:transport-api"))
	implementation(platform("androidx.compose:compose-bom:${libs.versions.compose.bom.get()}"))
	implementation("androidx.activity:activity-compose:${libs.versions.activity.compose.get()}")
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-tooling-preview")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${libs.versions.lifecycle.get()}")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycle.get()}")
	implementation("androidx.core:core-ktx:1.15.0")
	testImplementation(kotlin("test"))
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test-jvm:1.9.0")
}

android {
	namespace = "com.netless.app"
	compileSdk = 35

	signingConfigs {
		create("release") {
			val file = providers.environmentVariable("NETLESS_KEYSTORE").orNull
			if (file != null) {
				storeFile = file(file)
				storePassword = providers.environmentVariable("NETLESS_STORE_PASSWORD").orNull
				keyAlias = providers.environmentVariable("NETLESS_KEY_ALIAS").orNull
				keyPassword = providers.environmentVariable("NETLESS_KEY_PASSWORD").orNull
			}
		}
	}
	val hasReleaseSigning = providers.environmentVariable("NETLESS_KEYSTORE").isPresent &&
		providers.environmentVariable("NETLESS_STORE_PASSWORD").isPresent &&
		providers.environmentVariable("NETLESS_KEY_ALIAS").isPresent &&
		providers.environmentVariable("NETLESS_KEY_PASSWORD").isPresent
	buildTypes {
		release { if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release") }
	}

	defaultConfig {
		applicationId = "com.netless.app"
		minSdk = 26
		targetSdk = 35
		versionCode = 1
		versionName = "0.1"
	}
}
