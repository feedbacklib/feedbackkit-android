plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the engine runs in an isolated worker classloader (see AbiDumpTask), and the
    // Kotlin Gradle plugin API is already on the consuming build's classpath.
    compileOnly(libs.kotlin.abi.tools.api)
    compileOnly(libs.kotlin.gradle.plugin.api)
    // The Android DSL for feedbackkit.android-library; AGP itself comes from the consuming build.
    compileOnly(libs.android.gradle.api)
}

gradlePlugin {
    plugins {
        register("abiValidation") {
            id = "feedbackkit.abi-validation"
            implementationClass = "io.github.feedbacklib.android.buildlogic.abi.AbiValidationPlugin"
        }
        register("androidLibrary") {
            id = "feedbackkit.android-library"
            implementationClass = "io.github.feedbacklib.android.buildlogic.library.AndroidLibraryConventionPlugin"
        }
    }
}
