plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.kapt") }
android {
    useLibrary("android.car")
    namespace = "kr.carlog.pleos"
    compileSdk = 34
    defaultConfig { applicationId = "kr.carlog.pleos"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    // Probe builds use separate app IDs and never alter the normal Mock app sandbox.
    val uxProbe = providers.gradleProperty("carlog.isolatedUxProbe").orNull == "true"
    val locationProbe = providers.gradleProperty("carlog.isolatedLocationProbe").orNull == "true"
    val vehicleProbe = providers.gradleProperty("carlog.isolatedVehicleProbe").orNull == "true"
    require(listOf(uxProbe, locationProbe, vehicleProbe).count { it } <= 1) { "Choose only one isolated probe" }
    if (uxProbe) {
        buildTypes.getByName("debug") { applicationIdSuffix = ".uxprobe" }
    }
    if (locationProbe) {
        buildTypes.getByName("debug") { applicationIdSuffix = ".locationprobe" }
        sourceSets.getByName("debug").manifest.srcFile("src/locationProbe/AndroidManifest.xml")
    }
    if (vehicleProbe) {
        buildTypes.getByName("debug") { applicationIdSuffix = ".vehicleprobe" }
        sourceSets.getByName("debug").manifest.srcFile("src/vehicleProbe/AndroidManifest.xml")
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("test").resources.srcDir("../docs/examples")
}
dependencies {
    if (providers.gradleProperty("carlog.isolatedLocationProbe").orNull == "true") {
        debugImplementation("ai.pleos.playground:FusedLocation:1.1.3")
    }
    if (providers.gradleProperty("carlog.isolatedVehicleProbe").orNull == "true") {
        debugImplementation("ai.pleos.playground:Vehicle:2.3.2")
    }
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("ai.pleos.playground:FusedLocation:1.1.3")
    androidTestImplementation("ai.pleos.playground:Vehicle:2.3.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
