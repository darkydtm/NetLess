plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.kotlin.android)
}

android {
	namespace = "com.netless.identity.data"
	compileSdk = 35
}

dependencies {
	api(project(":domain:identity"))
	implementation(project(":core:crypto"))
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
	testImplementation(kotlin("test"))
}
