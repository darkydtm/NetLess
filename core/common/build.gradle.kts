plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.netless.common"
	compileSdk = 35
}

dependencies {
	testImplementation(kotlin("test"))
}
