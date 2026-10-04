/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.exoplayer

import android.net.Uri

internal object CarMediaIds {
	const val ROOT = "navic:root"
	const val RECENT = "navic:recent"
	const val ALBUMS = "navic:albums"
	const val ARTISTS = "navic:artists"
	const val LIBRARY = "navic:library"
	const val PLAYLISTS = "navic:playlists"
	const val RADIOS = "navic:radios"

	fun item(
		type: String,
		id: String,
		albumId: String? = null,
		playlistId: String? = null,
		position: Int? = null
	): String = Uri.Builder()
		.scheme("navic")
		.authority(type)
		.appendPath(id)
		.apply {
			albumId?.let { appendQueryParameter("album", it) }
			playlistId?.let { appendQueryParameter("playlist", it) }
			position?.let { appendQueryParameter("position", it.toString()) }
		}
		.build()
		.toString()

	fun parse(id: String): Item? {
		val uri = Uri.parse(id)
		if (uri.scheme != "navic" || uri.pathSegments.size != 1) return null
		val type = uri.authority?.takeIf {
			it in setOf("song", "album", "artist", "playlist", "radio")
		} ?: return null
		return Item(
			type,
			uri.pathSegments.single(),
			uri.getQueryParameter("album"),
			uri.getQueryParameter("playlist"),
			uri.getQueryParameter("position")?.toIntOrNull()?.takeIf { it >= 0 }
		)
	}

	data class Item(
		val type: String,
		val id: String,
		val albumId: String?,
		val playlistId: String? = null,
		val position: Int? = null
	)
}

/** Legacy car browsers may request Int.MAX_VALUE items; never multiply page sizes as Ints. */
internal fun <T> List<T>.carPage(page: Int, pageSize: Int): List<T> {
	require(page >= 0 && pageSize > 0)
	val start = page.toLong() * pageSize
	if (start >= size) return emptyList()
	return subList(start.toInt(), minOf(start + pageSize, size.toLong()).toInt())
}
