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
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
