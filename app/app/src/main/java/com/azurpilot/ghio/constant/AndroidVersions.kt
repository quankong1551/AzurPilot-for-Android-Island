package com.azurpilot.ghio.constant

import android.os.Build

/**
 * 集中列出各 Android 版本对应的 API 级别，供 `Build.VERSION.SDK_INT` 比较取用
 *
 * 每项与 `Build.VERSION_CODES` 同名对应，值即官方 API 级别；成员名自说明，
 * 不再逐项注释。
 *
 * Lists the API level of each Android release for use in
 * `Build.VERSION.SDK_INT` comparisons.
 *
 * Each entry mirrors its `Build.VERSION_CODES` twin and holds the official API
 * level; member names are self-describing, so no per-entry notes.
 */
object AndroidVersions {
    const val API_21_ANDROID_5_0 = Build.VERSION_CODES.LOLLIPOP
    const val API_22_ANDROID_5_1 = Build.VERSION_CODES.LOLLIPOP_MR1
    const val API_23_ANDROID_6_0 = Build.VERSION_CODES.M
    const val API_24_ANDROID_7_0 = Build.VERSION_CODES.N
    const val API_25_ANDROID_7_1 = Build.VERSION_CODES.N_MR1
    const val API_26_ANDROID_8_0 = Build.VERSION_CODES.O
    const val API_27_ANDROID_8_1 = Build.VERSION_CODES.O_MR1
    const val API_28_ANDROID_9 = Build.VERSION_CODES.P
    const val API_29_ANDROID_10 = Build.VERSION_CODES.Q
    const val API_30_ANDROID_11 = Build.VERSION_CODES.R
    const val API_31_ANDROID_12 = Build.VERSION_CODES.S
    const val API_32_ANDROID_12L = Build.VERSION_CODES.S_V2
    const val API_33_ANDROID_13 = Build.VERSION_CODES.TIRAMISU
    const val API_34_ANDROID_14 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    const val API_35_ANDROID_15 = Build.VERSION_CODES.VANILLA_ICE_CREAM
}