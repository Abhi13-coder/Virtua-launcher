package com.virtua.launcher.features

data class FeatureSpec(
    val id: String,
    val name: String,
    val version: String,
    val set: String,
    val abi: String,
    val soFile: String,
    val sha256: String,
    val replaces: List<String> = emptyList(),
    val enabledDefault: Boolean = true,
    val description: String = ""
)

data class LocalFeature(
    val spec: FeatureSpec,
    var enabled: Boolean,
    val path: String,
    val bundled: Boolean
)
