plugins {
    alias(libs.plugins.tvku.android.library)
    alias(libs.plugins.tvku.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tvku.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
