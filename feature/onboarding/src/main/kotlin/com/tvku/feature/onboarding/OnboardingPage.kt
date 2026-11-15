package com.tvku.feature.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tvku.core.designsystem.R as DsR

internal enum class OnboardingPage(
    @param:DrawableRes val icon: Int?,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
) {
    Welcome(null, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    Global(DsR.drawable.ic_tvku_globe, R.string.onboarding_global_title, R.string.onboarding_global_body),
    Verified(DsR.drawable.ic_tvku_shield, R.string.onboarding_verified_title, R.string.onboarding_verified_body),
    Premium(DsR.drawable.ic_tvku_heart, R.string.onboarding_premium_title, R.string.onboarding_premium_body),
    Privacy(DsR.drawable.ic_tvku_lock, R.string.onboarding_privacy_title, R.string.onboarding_privacy_body),
}
