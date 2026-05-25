plugins {
    // Kotlin version inherits from the root build (already on classpath).
    kotlin("jvm")
    application
}

application {
    mainClass.set("dev.lyo.hortay.tdapi.gen.MainKt")
}

kotlin {
    jvmToolchain(21)
}

// Convenience task: regenerate iosArm64Main/.../TdApi.kt from the staged
// td_api.tl (produced by scripts/build-tdlib-apple.sh). Default input/output
// resolve from rootProject — override via `-Pinput=...` / `-Poutput=...`.
tasks.register<JavaExec>("generate") {
    group = "build"
    description = "Generate shared/src/iosArm64Main/.../TdApi.kt from td_api.tl"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    val input = providers.gradleProperty("input")
        .orElse(rootProject.layout.projectDirectory.file("libtdlib/build/apple/td_api.tl").asFile.absolutePath)
    val output = providers.gradleProperty("output")
        .orElse(rootProject.layout.projectDirectory.file("shared/src/commonMain/kotlin/dev/lyo/hortay/tdlib/TdApi.kt").asFile.absolutePath)
    args(input.get(), output.get())
}
