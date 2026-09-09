import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.jvm.JvmTargetValidationMode

// The Compose Multiplatform desktop client. A parallel front end to the Swing `chat-desktop`
// module: it reuses `core-shared` (the Java protocol + crypto) unchanged and never touches Swing.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.7.3"
}

repositories {
    google()
    mavenCentral()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // The whole reason to pick Compose Multiplatform over a web stack: the Java protocol core is
    // consumed directly, with no reimplementation.
    implementation(project(":core-shared"))
}

// Kotlin output targets JVM 17 and is compiled by whatever JDK runs the build (JDK 25 here).
// `core-shared` stays Java 8; a JVM-17 module consuming Java-8 bytecode is fine.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    // The root build pins every module's javac to release 8; this module has no Java sources, so
    // there is nothing to actually be inconsistent with. Downgrade Kotlin's cross-task JVM-target
    // check from error to warning here only — every Java module is unaffected.
    jvmTargetValidationMode.set(JvmTargetValidationMode.WARNING)
}

// Checkstyle and SpotBugs + find-sec-bugs are wired for Java in the root build. They have nothing
// meaningful to say about Kotlin, and find-sec-bugs aborts on Kotlin's synthetic bytecode. Disable
// them for THIS module only; every Java module keeps both gates exactly as before.
tasks.matching {
    it.name.contains("checkstyle", ignoreCase = true) ||
        it.name.contains("spotbugs", ignoreCase = true)
}.configureEach { enabled = false }

compose.desktop {
    application {
        mainClass = "com.e2eechat.desktop.compose.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Tetherless"
            packageVersion = "1.0.0"
        }
    }
}
