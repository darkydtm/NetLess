plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "com.netless.database"
	compileSdk = 35
}

dependencies {
	implementation(project(":core:common"))
	implementation(project(":core:protocol"))
	testImplementation(kotlin("test"))
}
