plugins {
    alias(libs.plugins.tvku.android.feature)
}

android {
    namespace = "com.tvku.feature.home"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:player"))
}
