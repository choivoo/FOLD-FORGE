import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Project templates live as plain files under src/main/templates. Java resources cannot be listed
// at runtime (especially on Android), so we generate an index file alongside them.
val generatedTemplateRes = layout.buildDirectory.dir("generated/templateResources")
val generateTemplateIndex by tasks.registering {
    val srcDir = layout.projectDirectory.dir("src/main/templates")
    inputs.dir(srcDir)
    outputs.dir(generatedTemplateRes)
    doLast {
        val root = srcDir.asFile
        val out = generatedTemplateRes.get().asFile
        out.deleteRecursively()
        val target = File(out, "foldforge/templates")
        root.copyRecursively(target, overwrite = true)
        val entries = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted().toList()
        File(target, "index.txt").writeText(entries.joinToString("\n"))
    }
}

sourceSets {
    main {
        resources.srcDir(generatedTemplateRes)
    }
}

tasks.named("processResources") { dependsOn(generateTemplateIndex) }

tasks.test {
    // The APK builder tests need the real player template APK.
    dependsOn(":player-template:assembleRelease")
    systemProperty(
        "foldforge.playerTemplateApk",
        rootProject.file("player-template/build/outputs/apk/release/player-template-release-unsigned.apk").absolutePath,
    )
    systemProperty("foldforge.buildTools", (System.getenv("ANDROID_HOME") ?: "/opt/android-sdk") + "/build-tools")
    // Optional: -PexportDir=... / -PapkOut=... copy test outputs out for external build verification.
    providers.gradleProperty("exportDir").orNull?.let { systemProperty("foldforge.exportDir", it) }
    providers.gradleProperty("apkOut").orNull?.let { systemProperty("foldforge.apkOut", it) }
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.jgit)
    api(libs.apksig)
    api(libs.anthropic.java)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
