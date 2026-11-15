plugins {
    alias(libs.plugins.tvku.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
}
