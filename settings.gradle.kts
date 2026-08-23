import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
	}
}

rootProject.name = "netless"

include(":app")
include(":core:common")
include(":core:protocol")
include(":core:transport-api")
include(":core:crypto")
include(":core:database")
include(":domain:identity")
include(":data:identity")
