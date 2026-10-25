plugins {
    kotlin("jvm") version "2.4.20"
}

repositories { mavenCentral() }

// No .kts application sources are compiled by this focused test project.
configurations.matching { it.name.startsWith("kotlinCompilerPluginClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-embeddable")
}

// Exercises the real installation/parser/slot sources without the separately
// built ArkUI-X runtime. Android classes are used for linking, never emulated.
val repository = rootDir.resolve("../..").canonicalFile
val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("Set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK")
val androidJar = file("$sdk/platforms").listFiles().orEmpty()
    .filter { it.resolve("android.jar").isFile }
    .maxByOrNull { it.name.removePrefix("android-").substringBefore('.').toIntOrNull() ?: 0 }
    ?.resolve("android.jar") ?: error("No Android SDK platform installed under $sdk")

layout.buildDirectory.set(repository.resolve("build/hap-core-tests"))
kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir(repository.resolve("app/src/main/java"))
            kotlin.include("com/kira/harmoid/hap/**", "com/kira/harmoid/logging/LogWriter.kt",
                "com/kira/harmoid/ProcessSlotManager.kt", "com/kira/harmoid/ProcessSlotStore.kt",
                "com/kira/harmoid/hdc/HdcProtocol.kt", "com/kira/harmoid/hdc/IncomingTransfer.kt",
                "com/kira/harmoid/hdc/HdcPaths.kt")
        }
        test {
            kotlin.srcDir(repository.resolve("app/src/test/java"))
            kotlin.include("com/kira/harmoid/hap/**", "com/kira/harmoid/ProcessSlotStoreTest.kt",
                "com/kira/harmoid/hdc/**")
        }
    }
}
sourceSets.test { resources.srcDir(repository.resolve("app/src/test/resources")) }

val androidLinkStubs = tasks.register<Jar>("androidLinkStubs") {
    archiveClassifier.set("android-link-stubs")
    from(zipTree(androidJar)) { exclude("org/json/**") }
}

dependencies {
    compileOnly(files(androidJar))
    // android.jar contains throwing org.json stubs; tests use the real JVM JSON
    // implementation while retaining Android types only for class linking.
    testRuntimeOnly(files(androidLinkStubs.flatMap { it.archiveFile }))
    implementation("org.json:json:${providers.gradleProperty("jsonVersion").getOrElse("20231013")}")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
