plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.detekt)
}

kotlin {
    // Kotlin compiler args. `-Xexpect-actual-classes` silences the Beta
    // warning that fires on every expect/actual class (PlatformLog,
    // DriverFactory, WebDatabaseProvider). The feature is stable in K2;
    // the warning is purely a "this is still labelled Beta" notice.
    //
    // `-Xskip-prerelease-check` lets us depend on CMP material3 1.5.0-alpha19
    // (pre-release annotations on top of stable APIs we already opted-in to).
    @Suppress("OPT_IN_USAGE")
    compilerOptions.freeCompilerArgs.addAll(
        "-Xexpect-actual-classes",
        "-Xskip-prerelease-check",
    )

    android {
        namespace = "dev.lyo.hortay"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }

        androidResources {
            enable = true
        }

        withHostTest {}
    }

    // iOS targets. Kotlin/Native compilation requires macOS + Xcode 16+; Gradle
    // configures the targets fine on any host (tasks appear in :shared:tasks),
    // but `compileKotlinIosArm64` / `linkDebugFrameworkIosArm64` only run on Mac.
    // TDLib is Android-only for v1 — iosMain provides a stub TdClient so the iOS
    // app boots straight into WebModeScaffold (guest-mode). Full TDLib iOS port
    // is a separate track (cinterop + cross-compiled libtdjni.xcframework).
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            // Static framework: smaller binary, no dyld lookups for shared-module
            // symbols. CMP's recommended default for iOS apps that don't expose
            // the framework to third-party Swift packages.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // CMP runtime + foundation, declared via DIRECT Maven coordinates
            // (org.jetbrains.compose.material3:material3 etc.) instead of the
            // `compose.material3` DSL accessor. The DSL accessor was deprecated
            // in CMP 1.12 and resolves to a stub variant that hides Material3
            // Expressive symbols on iOS — going through the real coords exposes
            // them. See `composeMultiplatform` block in libs.versions.toml for
            // the full rationale.
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.ui.backhandler)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)

            // Ksoup — KMP HTML parser, replaces Jsoup. Same API surface
            // (Jsoup.parse, Document/Element/Node, CSS selectors).
            implementation(libs.ksoup)

            // Ktor — KMP HTTP client. Core stays platform-agnostic; engines
            // bind in androidMain (OkHttp) and iosMain (Darwin) below.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            // DataStore Preferences (KMP core variant). Platform-specific
            // factory creates DataStore<Preferences> via expect/actual.
            implementation(libs.androidx.datastore.preferences.core)
            // Okio for the platform-neutral Path used by DataStore's KMP factory.
            implementation(libs.okio)

            // SQLDelight coroutines extensions — asFlow() + mapToList bridges.
            // Multiplatform, lives in commonMain. Driver impls per-platform below.
            implementation(libs.sqldelight.coroutines.extensions)

            // Compottie — KMP Lottie renderer. Replaces lottie-compose so TGS
            // custom-emoji + sticker animations render on iOS too. Pure-Kotlin
            // renderer, no Android Canvas dependency.
            implementation(libs.compottie)
            implementation(libs.compottie.network)

            // Coil 3 is KMP — Compose adapter + Ktor3 network fetcher work on
            // both Android (OkHttp engine) and iOS (Darwin engine). Used by
            // the iOS guest-mode feed UI for channel avatars + photo posts.
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            // androidx.graphics.shapes 1.1.0 is KMP-published with iOS support —
            // Morph / RoundedPolygon / toPath are accessible on every target,
            // so the shared theme's PressedSelectedShape interpolation works
            // identically on Android and iOS.
            implementation(libs.androidx.graphics.shapes)
        }

        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.datastore.preferences)

            implementation(libs.compose.ui)
            implementation(libs.compose.ui.graphics)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.text.google.fonts)

            implementation(libs.kotlinx.coroutines.android)

            implementation(libs.coil.gif)
            implementation(libs.coil.video)

            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.media3.common)

            // OkHttp stays — Ktor's android engine wraps it, and Coil's
            // image fetcher still uses it directly. AppGraph creates the
            // OkHttp.Builder() that Ktor's HttpClient(OkHttp) reuses for
            // disk caching + connection pool config.
            implementation(libs.okhttp)
            implementation(libs.ktor.client.okhttp)

            implementation("androidx.browser:browser:1.8.0")

            implementation(libs.sqldelight.android.driver)

            implementation(project(":libtdlib"))

            implementation(libs.androidx.profileinstaller)
        }

        val androidHostTest by getting {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // iosMain intermediate source set — code shared between iosArm64 and
        // iosSimulatorArm64. Per-target source sets (iosArm64Main, etc.) are
        // auto-created by the KMP plugin; we wire them to depend on iosMain
        // so platform-specific actuals can live in one place.
        val iosMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Darwin engine for Ktor — uses NSURLSession on iOS.
                // Apple-side connection pooling + caching come from URLCache,
                // configured via the engine block at construction time.
                implementation(libs.ktor.client.darwin)

                // Native SQLDelight driver for iOS targets — wraps the system
                // sqlite3 library via cinterop. Same query API as
                // AndroidSqliteDriver, different open/close lifecycle.
                implementation(libs.sqldelight.native.driver)
            }
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
    }
}

composeCompiler {
    // Stability config applies to all targets. Reports/metrics writers crash on
    // Windows when targeting iOS (Compose compiler JsonBuilder writes filenames
    // with colons, which NTFS forbids). Re-enable per-target via task-level
    // config if needed — for now Android stability inspection works via
    // assembleDebug logs.
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_stability.conf")
    )
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    exclude("**/build/**", "**/generated/**", "**/sqldelight/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(false)
    }
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
}

sqldelight {
    databases {
        create("WebDatabase") {
            packageName.set("dev.lyo.hortay.data.web.db")
            verifyMigrations.set(true)
            generateAsync.set(false)
            // Multiplatform .sq sources now in commonMain so iOS native-driver
            // generates the same WebDatabase class. Default srcDir for KMP
            // SQLDelight is `src/<sourceSet>/sqldelight` — explicit assignment
            // documents the move.
            srcDirs("src/commonMain/sqldelight")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}

dependencies {
    detektPlugins(libs.detekt.rules.compose)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
