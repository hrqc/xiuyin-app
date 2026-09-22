import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun gitValue(vararg args: String): String = try {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "-c", "safe.directory=${rootDir.absolutePath}", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { if (args.firstOrNull() == "describe") "not-tagged-at-build" else "uncommitted" }
} catch (_: Exception) { "uncommitted" }

fun gitWorktreeState(): String = try {
    val status = providers.exec {
        workingDir(rootDir)
        commandLine("git", "-c", "safe.directory=${rootDir.absolutePath}", "status", "--porcelain", "--untracked-files=normal")
        isIgnoreExitValue = true
    }
    if (status.result.get().exitValue != 0) "UNKNOWN"
    else if (status.standardOutput.asText.get().isBlank()) "CLEAN" else "MODIFIED"
} catch (_: Exception) { "UNKNOWN" }

android {
    namespace = "com.xiuyin.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.xiuyin.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0-octave.2-fieldtest.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUILD_TIME", "\"${Instant.now()}\"")
        // Intended delivery tag. GIT_TAG separately records whether that tag existed at build time.
        buildConfigField("String", "FIELD_TAG", "\"v0.3.0-octave.2-fieldtest.1\"")
        buildConfigField("String", "GIT_COMMIT", "\"${gitValue("rev-parse", "HEAD")}\"")
        buildConfigField("String", "GIT_WORKTREE_STATE", "\"${gitWorktreeState()}\"")
        buildConfigField("String", "GIT_TAG", "\"${gitValue("describe", "--tags", "--exact-match", "HEAD")}\"")
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("tools/local/signing/xiuyin-debug.keystore")
    }
    sourceSets.getByName("main").assets.srcDirs(rootProject.file("tools/local/piano-model/bundle-assets"), rootProject.file("tools/local/omr-probe/bundle-assets"))
    androidResources { noCompress += listOf("tflite", "onnx") }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true }
    testOptions { animationsDisabled = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}
dependencies {
    // Verified local official runtimes; binary hashes and source URLs: libs/runtime-manifest.json.
    implementation(files("libs/litert-2.1.6.aar", "libs/litert-api-2.1.6.aar", "libs/onnxruntime-android-1.27.0.aar"))
    // Local CPU inference does not use AiPackModelProvider or Play model delivery.
    implementation(files("libs/guava-33.5.0-android.jar", "libs/failureaccess-1.0.3.jar", "libs/kotlinx-coroutines-guava-1.11.0.jar"))
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    // 2.11 requires API 37 + AGP 9.1; keep the API 36 compatible release.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    // Same compatibility pin; this suppresses only the newer-version advisory.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}



tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

// Full Guava supplies ListenableFuture; exclude the tiny legacy duplicate interface jar.
configurations.configureEach { exclude(group = "com.google.guava", module = "listenablefuture") }
