plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.vault.custode"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.custode.vault"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
    }

    // Firma: la chiave NON sta nel repository. Si passa da variabili d'ambiente.
    val ksPath = System.getenv("CUSTODE_KEYSTORE")
    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("CUSTODE_KS_PASS")
            keyAlias = System.getenv("CUSTODE_KEY_ALIAS") ?: "custode"
            keyPassword = System.getenv("CUSTODE_KEY_PASS") ?: System.getenv("CUSTODE_KS_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Il nucleo KDBX (scritto da zero) è condiviso con la libreria JVM testata contro KeePassXC.
    sourceSets.getByName("main").java.srcDir("../../custode-core/src/main/kotlin")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES") }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.fragment:fragment:1.6.2") // il default di biometric è troppo vecchio per le API ActivityResult
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
