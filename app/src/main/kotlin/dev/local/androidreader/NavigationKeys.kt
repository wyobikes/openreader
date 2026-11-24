package dev.local.androidreader

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Home : NavKey

@Serializable data class Reader(val path: String) : NavKey

/** A book read page by page from a server, without downloading the file. */
@Serializable data class OnlineReader(val sourceId: String, val bookId: String, val title: String) : NavKey
