plugins {
    alias(libs.plugins.tvku.android.library)
    alias(libs.plugins.tvku.android.hilt)
}

android {
    namespace = "com.tvku.core.security"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.datastore.preferences)
}
