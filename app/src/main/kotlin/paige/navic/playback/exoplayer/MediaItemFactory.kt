/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.playback.exoplayer

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import paige.navic.database.dao.RadioDao
import paige.navic.database.dao.SongDao
import paige.navic.database.mapper.toDomainModel
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainRadio
import paige.navic.domain.model.DomainSong
import paige.navic.playback.exoplayer.impl.cache.NavicDataSource.Companion.buildCoverUri
import paige.navic.playback.exoplayer.impl.cache.NavicDataSource.Companion.buildSongUri
import kotlin.time.Duration

/** The phone and car resolve the same downloads, metadata and transcoding preferences. */
class MediaItemFactory(
	private val sessionManager: SessionManager,
	private val songDao: SongDao,
	private val radioDao: RadioDao
) {
	suspend fun resolveSongs(ids: List<String>): List<DomainSong> {
		val songs = songDao.getSongsByIds(ids).associate { it.songId to it.toDomainModel() }
		val radioIds = ids.filter { it.startsWith("radio_") && it !in songs }
			.map { it.removePrefix("radio_") }
		val radios = if (radioIds.isEmpty()) emptyMap() else radioDao.getRadiosByIds(radioIds)
			.map { it.toDomainModel().toPlaybackSong() }.associateBy { it.id }
		return ids.mapNotNull { songs[it] ?: radios[it] }
	}

	fun metadata(
		song: DomainSong,
		artworkUri: Uri? = song.coverArtId?.let { sessionManager.getCoverArtUrl(it).toUri() }
	): MediaMetadata = with(song) {
		val displayArtist = artists.joinToString { it.name }.ifBlank { artistName }
		MediaMetadata.Builder()
			.setTitle(title)
			.setSubtitle(displayArtist)
			.setArtist(displayArtist)
			.setAlbumArtist(albumArtists.joinToString { it.name }.ifBlank { artistName })
			.setAlbumTitle(albumTitle)
			.setDurationMs(if (id.startsWith("radio_")) null else duration.inWholeMilliseconds)
			.setMediaType(
				if (id.startsWith("radio_")) MediaMetadata.MEDIA_TYPE_RADIO_STATION
				else MediaMetadata.MEDIA_TYPE_MUSIC
			)
			.setIsBrowsable(false)
			.setIsPlayable(true)
			.setArtworkUri(artworkUri)
			.build()
	}

	fun create(song: DomainSong): MediaItem {
		val uri = if (song.id.startsWith("radio_") && !song.filePath.isNullOrEmpty()) {
			song.filePath.toUri()
		} else {
			song.id.buildSongUri()
		}
		return MediaItem.Builder()
			.setMediaId(song.id)
			.setUri(uri)
			.setMediaMetadata(metadata(song, song.coverArtId?.buildCoverUri()))
			.apply {
				if (song.id.startsWith("radio_")) {
					setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
				}
			}
			.build()
	}
}

/** A stable queue entry shared by phone playback, Android Auto and saved player state. */
fun DomainRadio.toPlaybackSong() = DomainSong(
	id = "radio_$id",
	title = name,
	artistName = "Live Radio",
	artistId = "",
	albumTitle = "Live Stream",
	albumId = null,
	parentId = null,
	comment = null,
	trackNumber = null,
	discNumber = null,
	isrc = emptyList(),
	year = null,
	genre = null,
	genres = emptyList(),
	moods = emptyList(),
	duration = Duration.ZERO,
	bpm = null,
	contributors = emptyList(),
	userRating = null,
	averageRating = null,
	bitRate = null,
	bitDepth = null,
	sampleRate = null,
	audioChannelCount = null,
	replayGain = null,
	fileSize = 0,
	fileExtension = null,
	mimeType = null,
	filePath = streamUrl,
	starredAt = null,
	coverArtId = coverArtId,
	musicBrainzId = null,
	explicitStatus = DomainExplicitStatus.Unknown,
	artists = emptyList(),
	albumArtists = emptyList()
)
