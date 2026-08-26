plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Detekt rather than ktlint, chosen after ktlint was tried and reverted.
 *
 * ktlint's default ruleset produced 628 findings here, of which 5 were real: 479 were pure wrapping
 * and signature preference, and 29 flagged every @Composable for being PascalCase, which is the
 * correct Compose convention. Detekt targets code smells rather than formatting, so it does not
 * fight the framework's naming or generate hundreds of layout opinions.
 */
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt.yml"))
    // Kotlin lives under src/main/java in an Android project.
    source.setFrom(files("src/main/java", "src/test/java"))
}

android {
    namespace = "dev.shivam.nfcexplorer"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.shivam.nfcexplorer"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // material-icons-extended is deliberately omitted: it is deprecated and no longer
    // BOM-managed (resolves to 1.7.8 against Compose 1.9.0). Nav icons are local vectors.
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.play.services.auth)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
