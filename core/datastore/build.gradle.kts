plugins {
    alias(libs.plugins.tvku.android.library)
    alias(libs.plugins.tvku.android.hilt)
}

android {
    namespace = "com.tvku.core.datastore"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.androidx.datastore.preferences)
}
