plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "dev.stillday.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.stillday.app"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("stilldayVersionCode").map(String::toInt).getOrElse(16)
        versionName = providers.gradleProperty("stilldayVersionName").getOrElse("1.0.14")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    signingConfigs {
        val keyFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
        if (keyFile != null) create("privateRelease") {
            storeFile = file(keyFile)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull ?: error("Release keystore password is missing")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull ?: error("Release key alias is missing")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull ?: error("Release key password is missing")
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("privateRelease") } }
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("update-fixtures"))
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation(project(":domain"))
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
