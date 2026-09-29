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

    buildTypes {
        // Release code (R8, not debuggable) signed with the debug key and profileable from the shell:
        // what the spec §10 build() budget is measured on. A debug build runs interpreted and says little.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
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
