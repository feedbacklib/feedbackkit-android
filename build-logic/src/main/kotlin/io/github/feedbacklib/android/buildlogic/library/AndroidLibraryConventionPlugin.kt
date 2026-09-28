package io.github.feedbacklib.android.buildlogic.library

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/**
 * What every FeedbackKit library module shares: Maven coordinates and platform levels from
 * gradle.properties, Java 11 bytecode (the host app compiles for 11), unit tests that see the merged
 * resources (Robolectric), the instrumentation runner, and the `release` publication with sources.
 * The module keeps its namespace, resource prefix, `kotlin {}` block and dependencies. Apply after
 * `com.android.library`.
 *
 * `LibraryExtension` is compiled against AGP's gradle-api but resolved at runtime from the consuming
 * build's classpath (the root project puts AGP there), like `KotlinCompileTool` in the ABI plugin.
 */
public class AndroidLibraryConventionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.library") { configure(project) }
    }

    private fun configure(project: Project) {
        fun property(name: String): String = project.providers.gradleProperty(name).get()
        // JitPack serves its own coordinates, com.github.<owner>.<repo>:<module>:<git ref>, and says so
        // in its environment; everywhere else the coordinates come from gradle.properties.
        fun env(name: String): String? = project.providers.environmentVariable(name).orNull
        val jitpack = env("JITPACK") != null && env("GROUP") != null && env("ARTIFACT") != null && env("VERSION") != null
        project.group = if (jitpack) "${env("GROUP")}.${env("ARTIFACT")}" else property("feedbackkit.group")
        project.version = if (jitpack) env("VERSION")!! else property("feedbackkit.version")

        project.extensions.configure(LibraryExtension::class.java) {
            compileSdk = property("feedbackkit.compileSdk").toInt()
            defaultConfig {
                minSdk = property("feedbackkit.minSdk").toInt()
                consumerProguardFiles("consumer-rules.pro")
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                // the host app compiles for Java 11; inline functions built for a higher target
                // cannot be inlined into it.
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            testOptions {
                unitTests {
                    // Robolectric needs the merged manifest and resources of the tested variant.
                    isIncludeAndroidResources = true
                }
            }
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        project.pluginManager.apply("maven-publish")
        project.extensions.configure(PublishingExtension::class.java) {
            publications.register("release", MavenPublication::class.java) {
                artifactId = project.name
                project.afterEvaluate { from(project.components.getByName("release")) }
            }
        }
    }
}
