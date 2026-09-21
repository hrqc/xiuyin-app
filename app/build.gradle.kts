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

android {
    namespace = "com.xiuyin.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.xiuyin.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${gitValue("rev-parse", "HEAD")}\"")
        buildConfigField("String", "GIT_TAG", "\"${gitValue("describe", "--tags", "--exact-match", "HEAD")}\"")
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("tools/local/signing/xiuyin-debug.keystore")
    }
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
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
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
