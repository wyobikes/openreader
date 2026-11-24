package dev.local.longbox

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

@Serializable data class Reader(val path: String) : NavKey
