/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.exoplayer

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import paige.navic.data.database.dao.DownloadDao
import paige.navic.data.database.dao.RadioDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entity.DownloadStatus
import paige.navic.data.database.mapper.toDomainModel
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainRadio
import paige.navic.domain.model.DomainSong
import java.io.File
import kotlin.time.Duration

/** The phone and car resolve the same downloads, metadata and transcoding preferences. */
class MediaItemFactory(
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	private val connectivityManager: ConnectivityManager,
	private val downloadDao: DownloadDao,
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

	fun metadata(song: DomainSong): MediaMetadata = with(song) {
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
			.setArtworkUri(coverArtId?.let { sessionManager.getCoverArtUrl(it).toUri() })
			.build()
	}

	suspend fun create(song: DomainSong): MediaItem {
		val download = downloadDao.getDownloadById(song.id)
		val localFile = download?.takeIf { it.status == DownloadStatus.DOWNLOADED }
			?.filePath?.let(::File)?.takeIf { it.isFile }
		val uri = when {
			song.id.startsWith("radio_") && !song.filePath.isNullOrEmpty() -> song.filePath.toUri()
			localFile != null -> localFile.toUri()
			else -> streamUri(song.id)
		}
		return MediaItem.Builder()
			.setMediaId(song.id)
			.setUri(uri)
			.setMediaMetadata(metadata(song))
			.apply {
				if (song.id.startsWith("radio_")) {
					setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
				}
			}
			.build()
	}

	private fun streamUri(id: String) = with(preferenceManager) {
		val cellular = connectivityManager.isCellular.value
		val bitrate = if (isAdvancedTranscodingActive) {
			if (cellular) customMaxBitrateCellular else customMaxBitrateWifi
		} else {
			if (cellular) streamingQualityCellular.bitrate else streamingQualityWifi.bitrate
		}
		val container = if (isAdvancedTranscodingActive) {
			if (cellular) customFormatCellular else customFormatWifi
		} else {
			if (cellular) streamingQualityCellular.container else streamingQualityWifi.container
		}
		sessionManager.api.getStreamUrl(id, bitrate, container?.takeIf { it.isNotBlank() })
			.toUri().buildUpon().appendQueryParameter("estimateContentLength", "true").build()
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
	coverArtId = null,
	musicBrainzId = null,
	explicitStatus = DomainExplicitStatus.Unknown,
	artists = emptyList(),
	albumArtists = emptyList()
)
