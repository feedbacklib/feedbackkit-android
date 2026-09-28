import io.github.feedbacklib.android.buildlogic.version.GenerateSdkVersionTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    id("feedbackkit.android-library")
    alias(libs.plugins.android.junit5)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    id("feedbackkit.abi-validation")
}

android {
    namespace = "io.github.feedbacklib.android"
    resourcePrefix = "feedbackkit_"

    buildFeatures {
        buildConfig = false
        compose = true
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // FeedbackKit's own modules use the contract between its artifacts.
        optIn.add("io.github.feedbacklib.android.spi.FeedbackKitSpi")
    }
}

// The SDK version as an internal Kotlin constant instead of BuildConfig.
// Registered per variant inside onVariants: AGP's addGeneratedSourceDirectory sets the
// registered task's outputDir itself (build/generated/<source-type>/<variant>/<task name>),
// so no outputDir is configured here.
androidComponents {
    onVariants { variant ->
        val generateSdkVersion = tasks.register<GenerateSdkVersionTask>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}SdkVersion",
        ) {
            // The SDK reports its own version, not the coordinate a repository built it under.
            sdkVersion.set(providers.gradleProperty("feedbackkit.version"))
            packageName.set("io.github.feedbacklib.android.internal.core")
        }
        // built-in Kotlin compiles .kt sources found in either the "kotlin" or "java" source set;
        // `sources.kotlin` is the semantically correct one and works here. Without it the version
        // constant would silently go missing, so fail the configuration instead.
        checkNotNull(variant.sources.kotlin) { "No Kotlin source set on variant ${variant.name}" }
            .addGeneratedSourceDirectory(generateSdkVersion, GenerateSdkVersionTask::outputDir)
    }
}

val expectedVersion = version.toString()
tasks.withType<Test>().configureEach {
    // Lets FeedbackKitInfoTest check the version travelled from gradle.properties into the code.
    systemProperty("feedbackkit.expectedVersion", expectedVersion)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.coroutines.bom))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    // Modifier.feedbackKitPrivate exposes Compose UI types, hence `api`.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)

    // The report screen (spec §6). Each is a POM entry for every consumer; the reasons are in the
    // stage 4 plan: Material 3 components, a Compose activity, ViewModel + SavedStateHandle.
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testRuntimeOnly(libs.junit.vintage.engine)

    // End-to-end on a real device/emulator: real WorkManager, real file system.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.foundation)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
