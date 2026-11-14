import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(projects.appUi)
    implementation(projects.agentCore)
    implementation(projects.providerSubsystem)
    implementation(projects.workspaceEngine)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.components.resources)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.kotlinx.serialization.json)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.agent.code"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "29.0.14206865"

    // The build id is the single version source: APP_BUILD "r<n>" -> versionCode n,
    // versionName "r<n>". Android needs a non-decreasing versionCode for updates,
    // and the in-app updater compares release tags (same string) with APP_BUILD.
    val appBuildCode = Regex("APP_BUILD\\s*=\\s*\"r(\\d+)\"")
        .find(file("../provider-subsystem/src/commonMain/kotlin/com/agent/code/opencode/OpenCodeClient.kt").readText())
        ?.groupValues?.get(1)?.toInt()
        // A silent 1 would downgrade the installed app and desync from the
        // release tag; a broken parse must break the build instead.
        ?: error("APP_BUILD \"r<NN>\" not found in OpenCodeClient.kt")

    // CI (and any scripted build) signs with a shared key so an APK built on
    // GitHub installs over the locally built one. Unset -> Gradle's debug key.
    val sharedStore = System.getenv("SIGNING_STORE_FILE")
    val sharedSigning = if (sharedStore == null) null else signingConfigs.create("shared") {
        storeFile = file(sharedStore)
        storePassword = System.getenv("SIGNING_STORE_PASSWORD")
        keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    }

    defaultConfig {
        applicationId = "com.agent.code"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appBuildCode
        versionName = "r$appBuildCode"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            if (sharedSigning != null) signingConfig = sharedSigning
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ponytail: build runs as root in the proot (umask 077) -> APK lands 600 (root-only).
    // The IDE's non-root save process cannot read it -> "failed to save apk".
    // Chmod packaged APKs world-readable so the save/copy-out step can read them.
    tasks.withType<com.android.build.gradle.tasks.PackageApplication>().configureEach {
        val rootFile = rootDir
        doLast {
            val apkDir = outputDirectory.asFile.get()
            // m4coding IDE hardcodes <root>/app/build/outputs/apk/<flavor> to locate the APK
            // (decompiled buildSignedOutput -> findBuildOutput). This project's app module is
            // `androidApp`, not `app`, so the resolver can't find the APK and silently skips the
            // save. Mirror the packaged APK into the path the IDE expects.
            val mirrorDir = File(rootFile, "app/build/outputs/apk/${apkDir.name}")
            apkDir.walkTopDown().filter { it.extension == "apk" }.forEach { apk ->
                apk.setReadable(true, false)
                ProcessBuilder("chmod", "644", apk.absolutePath).start().waitFor()
                mirrorDir.mkdirs()
                apk.copyTo(mirrorDir.resolve(apk.name), overwrite = true)
                ProcessBuilder("chmod", "644", mirrorDir.resolve(apk.name).absolutePath).start().waitFor()
            }
            // build runs as root in the proot (umask 077) -> output dirs land 700, blocking the
            // non-root IDE save process from traversing to the APK. Open the mirror + real trees.
            ProcessBuilder("chmod", "-R", "a+rX", File(rootFile, "app").absolutePath).start().waitFor()
            var dir: File? = apkDir
            while (dir != null) {
                ProcessBuilder("chmod", "a+rX", dir.absolutePath).start().waitFor()
                if (dir.name == "androidApp") break
                dir = dir.parentFile
            }
        }
    }
}
