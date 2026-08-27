plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.netless.identity"
	compileSdk = 35
}

dependencies {
	api(project(":core:common"))
	api(project(":core:crypto"))
	api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
	testImplementation(kotlin("test"))
}
