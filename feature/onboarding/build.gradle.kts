plugins {
    alias(libs.plugins.tvku.android.feature)
}

android {
    namespace = "com.tvku.feature.onboarding"
}

dependencies {
    implementation(project(":core:datastore"))
}
