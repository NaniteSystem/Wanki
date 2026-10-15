// All Gradle plugins live on the root classpath so AGP and the Kotlin plugin share one classloader.
// AGP is only added when an Android SDK is present (settings.gradle.kts includes :app on the same
// condition), so the JVM modules still build where Google's Maven repository is unreachable.
buildscript {
    val localSdk = rootDir.resolve("local.properties").takeIf { it.exists() }
        ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
    val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: localSdk
    val android = androidSdk != null && File(androidSdk).exists()

    repositories {
        if (android) {
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        if (android) {
            classpath(libs.android.gradle.plugin)
            classpath(libs.kotlin.compose.gradle.plugin)
            classpath(libs.ksp.gradle.plugin)
        }
    }
}
