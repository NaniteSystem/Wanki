import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM on purpose: scoring has no Android dependencies, so it is unit-tested on the JVM
// and consumed as-is by the Android modules. ML Kit results come in as plain candidate lists.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
