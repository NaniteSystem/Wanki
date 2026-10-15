pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Wanki"

include(":core:scoring")
include(":core:ink")
include(":core:srs")

// Android modules need an SDK. Without one (e.g. a plain JVM container) only the pure-JVM modules
// are built; CI and Android Studio always have it.
val localSdk = file("local.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: localSdk
if (androidSdk != null && file(androidSdk).exists()) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found: building JVM modules only")
}
