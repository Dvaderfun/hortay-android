plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Dual jniLibs source: ALWAYS include the committed stripped libs from
    // src/main/jniLibs (a fresh `git clone` should build immediately, no
    // toolchain prerequisites). Optionally OVERRIDE them with unstripped
    // counterparts from build/tdlib-unstripped/ when a local `update-tdlib.sh`
    // run produced them.
    //
    // Why dual instead of "always unstripped":
    //   • Unstripped libtdjni.so is ~50× larger (~200 MB per ABI). Committing
    //     would balloon repo history forever and break clone speed.
    //   • Stripped lib alone is enough for app correctness — AGP would just
    //     report "no debug symbols" for production releases without the
    //     overlay. That's fine for dev builds, only release uploads care.
    //   • "Last srcDir wins" semantics: AGP merges these directories in order,
    //     and the LATER entry's same-named .so overrides the earlier one for
    //     packaging AND for AGP's `debugSymbolLevel = "FULL"` extraction. So
    //     when the overlay is populated, AGP extracts symbols from the
    //     unstripped copy and bundles them into BUNDLE-METADATA's native
    //     debug section automatically — no manual upload step.
    //
    // The overlay path is gitignored (everything under build/ is). Production
    // releases run `./scripts/update-tdlib.sh` (which now defaults
    // KEEP_DEBUG=1) → unstripped libs land in build/tdlib-unstripped/ →
    // `bundleRelease` picks them up → AAB ships full symbols → Play Console
    // gets correctly symbolicated native crash / ANR stacks for libtdjni.so.
    // AGP 9 forbids duplicate .so across srcDirs. Pick unstripped overlay (full
    // debug symbols for release AAB) when present, else committed stripped libs.
    sourceSets {
        getByName("main") {
            val unstripped = file("build/tdlib-unstripped")
            val hasUnstripped = unstripped.exists() &&
                unstripped.walkTopDown().any { it.name == "libtdjni.so" }
            // setSrcDirs (replace) not srcDirs (append): the default convention
            // dir src/main/jniLibs is already a source. Appending the overlay
            // would feed BOTH copies of libtdjni.so to mergeJniLibFolders, which
            // AGP 9 rejects as a duplicate resource (it no longer does
            // "last srcDir wins"). Replace the set so exactly one source remains.
            jniLibs.setSrcDirs(listOf(if (hasUnstripped) "build/tdlib-unstripped" else "src/main/jniLibs"))
        }
    }
}

dependencies {
    // TdApi.java is post-processed by AddIntDef.php to sprinkle @IntDef/@LongDef
    // annotations from androidx.annotation. They're SOURCE-retention only, so
    // compileOnly is enough — no runtime jar shipped.
    compileOnly("androidx.annotation:annotation:1.10.0")
}
