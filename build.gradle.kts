plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.detekt) apply false
}

// Static analysis for Kotlin (fossil does not parse .kt). Runs on every module;
// reports land in <module>/build/reports/detekt/. `ignoreFailures` keeps the
// existing `build`/`check` green while the codebase is cleaned up — flip to
// false (or set failOnSeverity) to make detekt a hard gate.
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        basePath.set(rootDir)
        parallel.set(true)
        // Stock ruleset + our overrides (config/detekt/detekt.yml).
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        ignoreFailures.set(true)
    }
    // detekt's default source dirs (src/main/kotlin, …) miss the KMP layout
    // (src/commonMain/kotlin, src/androidMain/kotlin, …), which leaves the
    // plain `detekt` task NO-SOURCE. Point it at every source set so a single
    // `./gradlew detekt` analyses all Kotlin in the module. Test source sets
    // are skipped — detekt is for production code.
    tasks.named<dev.detekt.gradle.Detekt>("detekt") {
        setSource(fileTree(projectDir) {
            include("src/**/*.kt", "src/**/*.kts")
            exclude(
                "**/commonTest/**", "**/androidHostTest/**",
                "**/androidInstrumentedTest/**", "**/androidUnitTest/**",
                "**/jsTest/**", "**/iosTest/**", "**/desktopTest/**"
            )
        })
    }
}