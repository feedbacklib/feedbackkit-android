package io.github.feedbacklib.android.buildlogic.abi

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.jetbrains.kotlin.abi.tools.AbiFilters
import org.jetbrains.kotlin.abi.tools.AbiTools

// These actions run in a classloader-isolated worker whose classpath is abi-tools 2.4.20 and its
// runtime dependencies, so the engine's Kotlin metadata reader never meets the Gradle/AGP classpath.

public abstract class JvmDumpAction : WorkAction<JvmDumpAction.Parameters> {

    public interface Parameters : WorkParameters {
        public val classFiles: ConfigurableFileCollection
        public val outputFile: RegularFileProperty
    }

    override fun execute() {
        val output = parameters.outputFile.get().asFile
        output.parentFile.mkdirs()
        // Sorted so the input order (and thus the dump) never depends on the file system.
        val classFiles = parameters.classFiles.files.sortedBy { it.invariantSeparatorsPath }
        output.bufferedWriter().use { writer ->
            AbiTools.getInstance().printJvmDump(writer, classFiles, FILTERS)
        }
    }

    private companion object {
        // The Compose compiler keeps non-capturing composable lambdas in `ComposableSingletons$<File>Kt`
        // holders that are always public on the JVM, even for internal composables. They are compiler
        // plumbing, not API a consumer can call, so they stay out of the dump.
        val FILTERS = AbiFilters(
            emptySet(),
            setOf("**.ComposableSingletons**"),
            emptySet(),
            emptySet(),
        )
    }
}

public abstract class DiffAction : WorkAction<DiffAction.Parameters> {

    public interface Parameters : WorkParameters {
        public val expectedFile: RegularFileProperty
        public val actualFile: RegularFileProperty
        public val outputFile: RegularFileProperty
    }

    override fun execute() {
        val diff = AbiTools.getInstance().filesDiff(
            parameters.expectedFile.get().asFile,
            parameters.actualFile.get().asFile,
        )
        parameters.outputFile.get().asFile.writeText(diff.orEmpty())
    }
}
