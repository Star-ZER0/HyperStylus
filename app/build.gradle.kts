plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "cc.star0.xposed.hyperstylus"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "cc.star0.xposed.hyperstylus"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { resources.merges += "META-INF/xposed/*" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { warningsAsErrors = false }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

val androidTestRuntime = configurations.create("androidTestRuntime")
val prepareAndroidTestRuntime = tasks.register<Copy>("prepareAndroidTestRuntime") {
    from(androidTestRuntime)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareAndroidTestRuntime)
    maxHeapSize = "1536m"
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

val runtimeConstraints = providers.fileContents(
    rootProject.layout.projectDirectory.file("gradle/runtime-constraints.txt")
).asText.map { contents ->
    contents.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
}

dependencies {
    implementation(libs.miuix.ui) {
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class-android")
    }
    implementation(libs.miuix.preference) {
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class-android")
    }
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.squircle)
    implementation(libs.miuix.shader)
    implementation(libs.libxposed.service)
    constraints {
        runtimeConstraints.get().forEach { coordinate -> add("implementation", coordinate) }
    }
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)

    // The framework supplies the API; packaging a second copy breaks module loading.
    compileOnly(libs.libxposed)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.monitor)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.espresso.core)
    testImplementation(libs.espresso.idling)
    testImplementation(libs.compose.test)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.libxposed)
    add(androidTestRuntime.name, libs.android15.test)
}
