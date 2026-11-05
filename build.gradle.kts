plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 owns Kotlin compilation. This selects its latest compatible KGP.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
