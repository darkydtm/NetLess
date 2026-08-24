import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.kotlin.android) apply false
}

subprojects {
	tasks.withType<JavaCompile>().configureEach {
		sourceCompatibility = JavaVersion.VERSION_17.toString()
		targetCompatibility = JavaVersion.VERSION_17.toString()
	}

	plugins.withId("com.android.application") {
		extensions.configure<ApplicationExtension> {
			compileOptions {
				sourceCompatibility = JavaVersion.VERSION_17
				targetCompatibility = JavaVersion.VERSION_17
			}
		}
	}

	plugins.withId("com.android.library") {
		extensions.configure<LibraryExtension> {
			compileOptions {
				sourceCompatibility = JavaVersion.VERSION_17
				targetCompatibility = JavaVersion.VERSION_17
			}
		}
	}

	plugins.withId("org.jetbrains.kotlin.android") {
		tasks.withType<KotlinCompile>().configureEach {
			compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
		}
	}
}
