import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.feedbacklib.android.sample"
    compileSdk = providers.gradleProperty("feedbackkit.compileSdk").get().toInt()

    defaultConfig {
        applicationId = "io.github.feedbacklib.android.sample"
        minSdk = providers.gradleProperty("feedbackkit.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("feedbackkit.targetSdk").get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("feedbackkit.version").get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":feedbackkit"))
    implementation(project(":feedbackkit-recording"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.material)
    debugImplementation(libs.compose.ui.tooling)
}
