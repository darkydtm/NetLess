plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.netless.crypto"
	compileSdk = 35
}

dependencies {
	testImplementation(kotlin("test"))
}
