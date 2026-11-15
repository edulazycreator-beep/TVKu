plugins {
    alias(libs.plugins.tvku.android.application)
    alias(libs.plugins.tvku.android.compose)
    alias(libs.plugins.tvku.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tvku.app"

    defaultConfig {
        applicationId = "com.tvku.app"
        versionCode = 2
        versionName = "0.2.0"
        // Interim public catalog published by .github/workflows/catalog.yml (until Supabase is online).
        buildConfigField(
            "String",
            "CATALOG_URL",
            "\"https://github.com/edulazycreator-beep/TVKu/releases/download/catalog/catalog-v1.json\"",
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release signing is configured in M11 from CI secrets; never committed.
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:datastore"))
    implementation(project(":core:security"))
    implementation(project(":core:player"))
    implementation(project(":core:data"))
    implementation(project(":feature:player"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
