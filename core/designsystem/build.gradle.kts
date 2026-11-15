plugins {
    alias(libs.plugins.tvku.android.library)
    alias(libs.plugins.tvku.android.compose)
}

android {
    namespace = "com.tvku.core.designsystem"
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
}
