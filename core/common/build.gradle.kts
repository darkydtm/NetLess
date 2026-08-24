plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "com.netless.common"
	compileSdk = 35
}

dependencies {
	testImplementation(kotlin("test"))
}
