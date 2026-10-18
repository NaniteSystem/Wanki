import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM: planning logic for texts learned by heart.
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
    implementation(project(":core:srs"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
