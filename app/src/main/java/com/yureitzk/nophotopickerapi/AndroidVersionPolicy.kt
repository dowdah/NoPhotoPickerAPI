package com.yureitzk.nophotopickerapi

import android.os.Build

/** Keeps Android 16 / HyperOS 3 hook and routing behavior separate from the upstream path. */
internal object AndroidVersionPolicy {
    const val ANDROID_16_API = 36
    private const val HYPEROS_3_INCREMENTAL_PREFIX = "OS3."

    private val LEGACY_SYSTEM_ACTIVITY_METHODS = listOf("startActivity")
    private val ANDROID_16_HYPEROS_3_SYSTEM_ACTIVITY_METHODS = listOf(
        "startActivity",
        "startActivityAsUser",
        "startActivityAndWait",
        "startActivityWithConfig",
        "startActivityAsCaller"
    )

    fun routingModeForSystem(
        sdkInt: Int = Build.VERSION.SDK_INT,
        incremental: String = Build.VERSION.INCREMENTAL
    ): PickerIntentTransformer.RoutingMode {
        return if (
            sdkInt == ANDROID_16_API &&
            incremental.startsWith(HYPEROS_3_INCREMENTAL_PREFIX, ignoreCase = true)
        ) {
            PickerIntentTransformer.RoutingMode.ANDROID_16_HYPEROS_3
        } else {
            PickerIntentTransformer.RoutingMode.LEGACY
        }
    }

    fun systemActivityMethodNames(
        sdkInt: Int = Build.VERSION.SDK_INT,
        incremental: String = Build.VERSION.INCREMENTAL
    ): List<String> {
        return if (routingModeForSystem(sdkInt, incremental) == PickerIntentTransformer.RoutingMode.ANDROID_16_HYPEROS_3) {
            ANDROID_16_HYPEROS_3_SYSTEM_ACTIVITY_METHODS
        } else {
            LEGACY_SYSTEM_ACTIVITY_METHODS
        }
    }
}
