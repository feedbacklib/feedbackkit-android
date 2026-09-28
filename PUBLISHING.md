# Publishing FeedbackKit

Two Android library artifacts (AAR, release variant only, with a sources jar):

| Module | Coordinate |
|---|---|
| `:feedbackkit` | `io.github.feedbacklib:feedbackkit:<version>` |
| `:feedbackkit-recording` | `io.github.feedbacklib:feedbackkit-recording:<version>` |

`<version>` is `feedbackkit.version` in `gradle.properties`. `feedbackkit-recording` depends on
`feedbackkit` of the same version.

`feedbackkit-recording` has no public API of its own: FeedbackKit finds it with ServiceLoader.
Adding it brings FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS and a
`mediaProjection` foreground service into the host's merged manifest; a host without it gets none of
them. FOREGROUND_SERVICE itself reaches every host already, through WorkManager (a `feedbackkit`
dependency).

`ScreenRecorderProvider.SPI_VERSION` must match between `feedbackkit` and `feedbackkit-recording`
of the same release. Both artifacts are published with the same version; a mismatched recorder is
ignored with a log line.

## Publishing locally

```bash
./gradlew publishToMavenLocal
```

Installs both artifacts into `~/.m2/repository`.

## Remote publishing

JitPack builds the artifacts from this repository by commit: `com.github.feedbacklib.feedbackkit-android:feedbackkit:<sha>`
(and `feedbackkit-recording`). A consumer can also substitute them with a local clone through a
composite build (`includeBuild`).

## Rules the build must keep

- **No local jars and no `files(...)` dependencies.** File dependencies are silently absent from
  the AAR and the POM, so the published artifact breaks at runtime for every consumer.
- **No dynamic versions** (`+`, `latest`) anywhere; every version lives in `gradle/libs.versions.toml`.
- The publication is `singleVariant("release")`; test and debug-only dependencies must not leak into it.
- Library bytecode targets **Java 11**, so hosts compiling for 11 can use it.
