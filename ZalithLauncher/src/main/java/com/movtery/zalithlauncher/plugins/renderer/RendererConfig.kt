package com.movtery.zalithlauncher.plugins.renderer

data class RendererConfig(
    val pluginVersion: Int,
    var rendererId: String,
    var rendererDisplayName: String,
    var glName: String,
    var eglName: String,
    val boatEnv: MutableMap<String, String>,
    val pojavEnv: MutableMap<String, String>
)