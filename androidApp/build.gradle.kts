import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

val telegramProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val telegramApiId: String = telegramProps.getProperty("telegram.apiId") ?: "0"
val telegramApiHash: String = telegramProps.getProperty("telegram.apiHash") ?: ""

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

gradle.taskGraph.whenReady {
    val needsSigning = allTasks.any { task ->
        task.project == project && task.name.matches(Regex("^(package|bundle)(Release|Beta).*"))
    }
    if (needsSigning && !keystoreProps.containsKey("storeFile")) {
        throw GradleException(
            "Release/Beta packaging requires keystore.properties at the project root.\n" +
                "Expected keys: storeFile, storePassword, keyAlias, keyPassword.\n" +
                "See androidApp/build.gradle.kts and ARCHITECTURE.md (Setup-delta) for details."
        )
    }
    val packagingTask = allTasks.firstOrNull { task ->
        task.project == project && task.name.matches(Regex("^(package|bundle)(Release|Beta).*"))
    }
    if (packagingTask != null) {
        val variant = if (packagingTask.name.contains("Beta")) "beta" else "release"
        val childSafety = (project.findProperty("HORTAY_CHILD_SAFETY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        val privacy = (project.findProperty("HORTAY_PRIVACY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        if (childSafety == null) {
            error("HORTAY_CHILD_SAFETY_POLICY_URL must be set in gradle.properties for $variant builds")
        }
        if (privacy == null) {
            error("HORTAY_PRIVACY_POLICY_URL must be set in gradle.properties for $variant builds")
        }
    }
}

val gitShortSha: String by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
    }.getOrDefault("unknown")
}
val gitCommitCount: Int by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)
}

android {
    namespace = "dev.lyo.hortay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lyo.hortay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.6.0"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        val childSafetyProp = (project.findProperty("HORTAY_CHILD_SAFETY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        val privacyProp = (project.findProperty("HORTAY_PRIVACY_POLICY_URL") as? String)?.takeIf { it.isNotBlank() }
        buildConfigField("String", "CHILD_SAFETY_POLICY_URL", "\"${childSafetyProp ?: "https://dev.lyo.hortay/child-safety"}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${privacyProp ?: "https://dev.lyo.hortay/privacy"}\"")
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
                debugSymbolLevel = "FULL"
            }
        }
        debug {
            isDebuggable = true
            ndk {
                abiFilters.clear()
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    baselineProfile {
        mergeIntoMain = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val isBeta = variant.buildType == "beta"
        val isRelease = variant.buildType == "release"
        variant.outputs.forEach { output ->
            if (isBeta || isRelease) {
                output.versionCode.set(gitCommitCount)
            }
            if (isBeta) {
                val base = output.versionName.orNull ?: "0.0.0"
                output.versionName.set("$base-$gitShortSha")
            }
            val versionName = output.versionName.orNull ?: "unversioned"
            output.outputFileName.set("hortay-$versionName-${variant.name}.apk")
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.material3)

    implementation(libs.coil.compose)

    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.leakcanary.android)

    "baselineProfile"(project(":baselineprofile"))
}
