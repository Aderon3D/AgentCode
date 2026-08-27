import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    id("org.jetbrains.kotlin.android")
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

    defaultConfig {
        applicationId = "com.agent.code"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
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
