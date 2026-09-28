plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Never applied: AGP 9 compiles Kotlin itself. Declared only to put KGP 2.4.20 on the
    // classpath so it wins over the 2.2.10 AGP bundles.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.junit5) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
