plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}


tasks.register("verifyEnvironment") {
    doLast {
        require(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
            "JDK 21 or newer is required"
        }
    }
}
