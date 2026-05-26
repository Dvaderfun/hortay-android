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

    // iOS target: arm64 device only. Kotlin/Native compilation requires
    // macOS + Xcode 16+. TDLib is cross-compiled for ios-arm64 via cinterop;
    // simulator slices are not supported (no libtdjson for simulator).
    iosArm64 {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        compilations.getByName("main").cinterops.create("tdjson") {
            defFile = project.file("src/iosArm64Main/cinterop/tdjson.def")
            packageName = "dev.lyo.hortay.tdlib.native"
            val tdlibAppleDir = rootProject.layout.projectDirectory
                .dir("libtdlib/build/apple/libtdlight.xcframework/ios-arm64")
            includeDirs(tdlibAppleDir.dir("Headers"))
            extraOpts("-libraryPath", tdlibAppleDir.asFile.absolutePath)
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

            // Koin (KMP DI). `koin-core` for module DSL + container; `koin-compose`
            // for KoinContext + koinInject; `koin-compose-viewmodel` for KMP
            // koinViewModel() that reads LocalViewModelStoreOwner so the existing
            // per-NavTarget ViewModelStoreOwner contract carries through unchanged
            // via nav3's rememberViewModelStoreNavEntryDecorator.
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // Navigation 3. `navigation3-runtime` (raw androidx, KMP-published)
            // ships NavKey + NavBackStack + NavEntry + NavEntryDecorator;
            // JetBrains `navigation3-ui` ships NavDisplay + Scene/SceneStrategy +
            // SinglePaneSceneStrategy default. `lifecycle-viewmodel-navigation3`
            // ships `rememberViewModelStoreNavEntryDecorator` for per-entry
            // ViewModelStoreOwner isolation (replaces Hortay's hand-rolled
            // pre-nav3 NavEntryHost). MainScaffold / WebModeScaffold mount
            // `NavDisplay(backStack = nav.entries, …)` with these decorators
            // and the default `SinglePaneSceneStrategy`; the previous layer
            // peek during predictive-back comes for free via NavDisplay's
            // AnimatedContent rendering the previous scene under the top.
            implementation(libs.navigation3.runtime)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.jetbrains.navigationevent.compose)
            implementation(libs.lifecycle.viewmodel.navigation3)
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

            // koin-android adds the Application-bound startKoin {
            // androidContext(this); androidLogger(...) } DSL. AndroidX-specific
            // bindings live here; the KMP koin-core + koin-compose stay in
            // commonMain.
            implementation(libs.koin.android)
        }

        val androidHostTest by getting {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // iosMain intermediate source set — code shared between iosArm64 and
        // iosSimulatorArm64. The default Kotlin hierarchy template (enabled
        // by default in K2) auto-creates `iosMain` and wires both
        // per-target sets to `dependsOn(iosMain)` for us, so we just attach
        // dependencies here.
        iosMain.dependencies {
            // Darwin engine for Ktor — uses NSURLSession on iOS. Apple-side
            // connection pooling + caching come from URLCache, configured
            // via the engine block at construction time.
            implementation(libs.ktor.client.darwin)

            // Native SQLDelight driver for iOS targets — wraps the system
            // sqlite3 library via cinterop. Same query API as
            // AndroidSqliteDriver, different open/close lifecycle.
            implementation(libs.sqldelight.native.driver)
        }
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
