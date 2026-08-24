plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "com.netless.transport"
	compileSdk = 35
}

dependencies {
	api(project(":core:common"))
	api(project(":core:crypto"))
	api(project(":core:protocol"))
	api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
	testImplementation(kotlin("test"))
}
