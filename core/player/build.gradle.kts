plugins {
    alias(libs.plugins.tvku.android.library)
    alias(libs.plugins.tvku.android.compose)
}

android {
    namespace = "com.tvku.core.player"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:designsystem"))
}
