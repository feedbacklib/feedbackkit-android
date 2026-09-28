# Consumer R8 rules shipped inside the AAR.
# FeedbackKit finds the recorder with ServiceLoader by the name in META-INF/services.
# R8 rewrites ServiceLoader.load(X::class.java, X::class.java.classLoader).iterator() into direct
# construction of the listed classes, but a host that shrinks without that rewrite still reads the
# services file at run time: keep the provider and its constructor, and the provider interface's
# name the services file is named after.
-keep class io.github.feedbacklib.android.recording.internal.RecordingProvider { public <init>(); }
-keepnames interface io.github.feedbacklib.android.spi.ScreenRecorderProvider
