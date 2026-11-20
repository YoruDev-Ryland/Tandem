package dev.emusync.app.model

import kotlinx.serialization.Serializable

@Serializable
data class ManifestEntry(val path: String, val hash: String, val size: Long)
