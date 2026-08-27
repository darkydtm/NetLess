plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.netless.protocol"
	compileSdk = 35
}

dependencies {
	api(project(":core:common"))
	testImplementation(kotlin("test"))
}
