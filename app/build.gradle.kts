plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":domain:identity"))
	implementation(project(":data:identity"))
	implementation(platform("androidx.compose:compose-bom:${libs.versions.compose.bom.get()}"))
	implementation("androidx.activity:activity-compose:${libs.versions.activity.compose.get()}")
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-tooling-preview")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${libs.versions.lifecycle.get()}")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycle.get()}")
	testImplementation(kotlin("test"))
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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
