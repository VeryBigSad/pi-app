import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseStorePath = providers.environmentVariable("PI_MOBILE_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("PI_MOBILE_KEYSTORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("PI_MOBILE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyPassword).all { !it.isNullOrBlank() }

val appVersion = Properties().apply {
    rootProject.file("gradle/app-version.properties").inputStream().use { load(it) }
}
val canonicalVersionCode: Int = appVersion.getProperty("versionCode")?.toIntOrNull()
    ?: error("gradle/app-version.properties missing numeric versionCode")
val canonicalVersionName: String = appVersion.getProperty("versionName")
    ?: error("gradle/app-version.properties missing versionName")

android {
    namespace = "io.github.verybigsad.pimobile"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.verybigsad.pimobile"
        minSdk = 29
        targetSdk = 36
        versionCode = canonicalVersionCode
        versionName = canonicalVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = "pimobile-release"
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":android:core:protocol"))
    implementation(project(":android:core:network"))
    implementation(project(":android:core:voice"))
    implementation(project(":android:core:push"))
    implementation(project(":android:core:model"))
    implementation(project(":android:core:security"))
    implementation(project(":android:core:storage"))
    implementation(project(":android:core:update"))
    implementation(project(":android:terminal"))
    implementation(project(":android:feature:session"))
    implementation(project(":android:feature:agents"))
    implementation(project(":android:feature:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
}
