import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    id("feedbackkit.android-library")
    alias(libs.plugins.android.junit5)
    id("feedbackkit.abi-validation")
}

android {
    namespace = "io.github.feedbacklib.android.recording"
    // Its own prefix, never plain "feedbackkit_": a resource of the same name in both AARs would
    // silently replace the core module's.
    resourcePrefix = "feedbackkit_recording_"
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // FeedbackKit's own modules use the contract between its artifacts.
        optIn.add("io.github.feedbacklib.android.spi.FeedbackKitSpi")
    }
}

dependencies {
    // No public API of its own: FeedbackKit finds the recorder through ServiceLoader.
    // `api` keeps feedbackkit at compile scope in the POM, so a host that declares only this
    // artifact still compiles against FeedbackKit.
    api(project(":feedbackkit"))

    // Pure logic (session state machine, segment ring, clip arithmetic) on the JVM.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Real MediaProjection on a device/emulator; UiAutomator taps the system consent dialog.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // compose-ui-test-junit4 pulls espresso-core 3.5.0, which breaks on API 37 (see the catalog).
    androidTestImplementation(libs.androidx.test.espresso.core)
}
