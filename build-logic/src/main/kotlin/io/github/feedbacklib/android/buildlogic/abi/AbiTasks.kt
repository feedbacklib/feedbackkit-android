package io.github.feedbacklib.android.buildlogic.abi

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/** Common part: dumps the ABI of [classFiles] with abi-tools in an isolated worker. */
public abstract class AbiDumpTask : DefaultTask() {

    /** `.class` files produced by the Kotlin compiler for the release variant. */
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val classFiles: ConfigurableFileCollection

    /** abi-tools and its runtime dependencies; kept off the Gradle/AGP classpath. */
    @get:Classpath
    public abstract val abiToolsClasspath: ConfigurableFileCollection

    @get:Inject
    protected abstract val workerExecutor: WorkerExecutor

    protected fun isolatedQueue(): WorkQueue = workerExecutor.classLoaderIsolation {
        classpath.from(abiToolsClasspath)
    }

    protected fun dumpTo(queue: WorkQueue, output: RegularFileProperty) {
        queue.submit(JvmDumpAction::class.java) {
            classFiles.from(this@AbiDumpTask.classFiles)
            outputFile.set(output)
        }
    }
}

@DisableCachingByDefault(because = "Writes into the source tree; regenerating is cheap.")
public abstract class UpdateAbiTask : AbiDumpTask() {

    @get:OutputFile
    public abstract val dumpFile: RegularFileProperty

    @TaskAction
    public fun update() {
        dumpTo(isolatedQueue(), dumpFile)
    }
}

@CacheableTask
public abstract class CheckAbiTask : AbiDumpTask() {

    /** The checked-in dump. A file collection rather than a file property so it may be missing. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val referenceDump: ConfigurableFileCollection

    /** Absolute path of the checked-in dump, for messages only. */
    @get:Internal
    public abstract val referenceDumpPath: Property<String>

    @get:OutputFile
    public abstract val actualDump: RegularFileProperty

    @get:OutputFile
    public abstract val diffFile: RegularFileProperty

    @TaskAction
    public fun verify() {
        val reference = File(referenceDumpPath.get())
        val actual = actualDump.get().asFile
        val diff = diffFile.get().asFile
        diff.delete()

        val queue = isolatedQueue()
        dumpTo(queue, actualDump)
        queue.await()

        if (!reference.isFile) {
            diff.writeText("")
            throw GradleException(
                "Public API dump is missing: $reference\n" +
                    "Run `./gradlew updateAbi` and commit the generated file.",
            )
        }
        if (normalized(reference) == normalized(actual)) {
            diff.writeText("")
            return
        }

        queue.submit(DiffAction::class.java) {
            expectedFile.set(reference)
            actualFile.set(actual)
            outputFile.set(diff)
        }
        queue.await()
        throw GradleException(
            "Public API has changed and no longer matches $reference:\n\n" +
                diff.readText() +
                "\n\nIf the change is intended, run `./gradlew updateAbi` and commit the updated dump.",
        )
    }

    private fun normalized(file: File): String = file.readText().replace("\r\n", "\n")
}
