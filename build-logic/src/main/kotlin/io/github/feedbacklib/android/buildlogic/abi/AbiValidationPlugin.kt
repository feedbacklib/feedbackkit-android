package io.github.feedbacklib.android.buildlogic.abi

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool

/**
 * Freezes the public API of an Android library module in `<module>/api/<module>.api` and
 * verifies it as part of `check`.
 *
 * Stop-gap for KT-83410: neither KGP's `abiValidation` nor binary-compatibility-validator sees
 * AGP 9's built-in Kotlin compilation, so this plugin drives the same engine
 * (`org.jetbrains.kotlin:abi-tools`, same version as the compiler) directly. Once KT-83410 is
 * fixed, switch to KGP's `abiValidation` and delete this package.
 */
public class AbiValidationPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.library") {
            configure(project)
        }
    }

    private fun configure(project: Project) {
        val abiTools = project.configurations.create("abiTools") {
            description = "Kotlin abi-tools engine, run in an isolated worker classloader."
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            }
        }
        val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        project.dependencies.add(abiTools.name, catalog.findLibrary("kotlin-abi-tools").get())

        val dumpFileName = "${project.name}.api"
        val checkedInDump = project.layout.projectDirectory.file("$API_DIR/$dumpFileName")

        val updateAbi = project.tasks.register("updateAbi", UpdateAbiTask::class.java) {
            group = TASK_GROUP
            description = "Writes the public API of the release variant to $API_DIR/$dumpFileName."
            classFiles.from(releaseKotlinClasses(project))
            abiToolsClasspath.from(abiTools)
            dumpFile.set(checkedInDump)
        }

        val checkAbi = project.tasks.register("checkAbi", CheckAbiTask::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks that the public API of the release variant matches $API_DIR/$dumpFileName."
            classFiles.from(releaseKotlinClasses(project))
            abiToolsClasspath.from(abiTools)
            referenceDump.from(checkedInDump)
            referenceDumpPath.set(checkedInDump.asFile.absolutePath)
            actualDump.set(project.layout.buildDirectory.file("abi/$dumpFileName"))
            diffFile.set(project.layout.buildDirectory.file("abi/$dumpFileName.diff"))
            // `./gradlew updateAbi check` must compare against the freshly written dump.
            mustRunAfter(updateAbi)
        }

        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
            dependsOn(checkAbi)
        }
    }

    /**
     * `.class` files of the release variant's Kotlin compile task, located via the task's own
     * `destinationDirectory` (AGP 9 puts it under build/intermediates/built_in_kotlinc/...).
     * Javac output (AGP's BuildConfig) lives elsewhere and is intentionally not part of the dump.
     * Called from task configuration actions, i.e. once AGP has registered its tasks.
     *
     * `KotlinCompileTool` is resolved from the consumer project's own classpath (AGP's built-in
     * Kotlin compilation), not from build-logic's — so this plugin only works applied to a module
     * that itself carries AGP/KGP in the same classloader; a module without one has no task named
     * [RELEASE_KOTLIN_COMPILE_TASK] to find.
     */
    private fun releaseKotlinClasses(project: Project): Provider<FileTree> {
        val compileTask = try {
            project.tasks.named(RELEASE_KOTLIN_COMPILE_TASK, KotlinCompileTool::class.java)
        } catch (e: UnknownTaskException) {
            throw GradleException(
                "feedbackkit.abi-validation expects a 'release' build type in ${project.path}",
                e,
            )
        }
        return compileTask
            .flatMap { it.destinationDirectory }
            .map { dir -> dir.asFileTree.matching { include("**/*.class") } }
    }

    private companion object {
        const val RELEASE_KOTLIN_COMPILE_TASK = "compileReleaseKotlin"
        const val API_DIR = "api"
        const val TASK_GROUP = "abi"
    }
}
