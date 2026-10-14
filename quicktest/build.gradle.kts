import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val sourceCommit = providers.exec {
    workingDir(rootDir)
    commandLine("git", "-c", "safe.directory=${rootDir.absolutePath}", "rev-parse", "HEAD")
}.standardOutput.asText.get().trim()

android {
    namespace = "com.xiuyin.quicktest"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.xiuyin.quicktest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-quicktest.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"$sourceCommit\"")
        buildConfigField("String", "BUILD_TIME", "\"${Instant.now()}\"")
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("tools/local/signing/xiuyin-debug.keystore")
    }
    // Compile the real, unchanged PCM-only engines directly. No OMR/library or score target enters this app.
    sourceSets.getByName("main") {
        java.srcDirs(
            rootProject.file("app/src/main/java/com/xiuyin/app/audio"),
            rootProject.file("app/src/main/java/com/xiuyin/app/pitch"),
        )
        assets.srcDir(rootProject.file("tools/local/piano-model/bundle-assets"))
    }
    androidResources { noCompress += "tflite" }
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
    implementation(files(rootProject.file("app/libs/litert-2.1.6.aar"), rootProject.file("app/libs/litert-api-2.1.6.aar")))
    implementation(files(rootProject.file("app/libs/guava-33.5.0-android.jar"), rootProject.file("app/libs/failureaccess-1.0.3.jar"), rootProject.file("app/libs/kotlinx-coroutines-guava-1.11.0.jar")))
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // API 36 compatible lifecycle baseline, shared with the delivered app.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
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
configurations.configureEach { exclude(group = "com.google.guava", module = "listenablefuture") }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
