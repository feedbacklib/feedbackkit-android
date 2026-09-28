// Convention plugins for the FeedbackKit build. Uses the root version catalog so every version
// stays in gradle/libs.versions.toml.
dependencyResolutionManagement {
    repositories {
        google {
            // com.android.tools.build:gradle-api (feedbackkit.android-library) lives only on
            // Google Maven; keep this repository scoped to Android/Google groups.
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
