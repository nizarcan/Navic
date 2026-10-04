/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.mapper.toDomainModel
import paige.navic.data.database.mapper.toEntity
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import paige.navic.ui.core.UiState
import dev.zt64.subsonic.api.model.AlbumInfo as ApiAlbumInfo

class CollectionRepository(
	private val albumDao: AlbumDao,
	private val playlistDao: PlaylistDao,
	private val songDao: SongDao,
	private val dbRepository: DbRepository,
	private val sessionManager: SessionManager,
	private val connectivityManager: ConnectivityManager
) {
	suspend fun getLocalData(collectionId: String): DomainSongCollection {
		return albumDao.getAlbumById(collectionId)?.toDomainModel()
			?: playlistDao.getPlaylistById(collectionId)?.toDomainModel()
			?: throw Error("Collection ID $collectionId is neither a known album or playlist")
	}

	suspend fun getAlbumSongs(id: String): List<DomainSong> {
		val album = albumDao.getAlbumById(id)
		val songs = songDao.getSongsByAlbumId(id).map { it.toDomainModel() }
		val incomplete = songs.isEmpty() || songs.size < (album?.album?.songCount ?: 0)
		val available = if (incomplete && connectivityManager.isOnline.value) {
			refreshAlbum(id).songs
		} else songs
		return available.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))
	}

	suspend fun getPlaylistSongs(id: String): List<DomainSong> {
		val playlist = requireNotNull(playlistDao.getPlaylistById(id)).toDomainModel()
		return if (playlist.songs.size < playlist.songCount && connectivityManager.isOnline.value) {
			refreshPlaylist(id).songs
		} else playlist.songs
	}

	private suspend fun refreshAlbum(id: String): DomainAlbum {
		val album = sessionManager.api.getAlbum(id)
		songDao.updateSongsByAlbumId(id, album.songs.map { it.toEntity() })
		albumDao.insertAlbum(album.toEntity())
		return requireNotNull(albumDao.getAlbumById(id)).toDomainModel()
	}

	private suspend fun refreshPlaylist(id: String): DomainPlaylist {
		val playlist = sessionManager.api.getPlaylist(id)
		playlistDao.insertPlaylist(playlist.toEntity())
		dbRepository.syncPlaylistSongs(id).getOrThrow()
		return requireNotNull(playlistDao.getPlaylistById(id)).toDomainModel()
	}

	private suspend fun refreshLocalData(collectionId: String): DomainSongCollection {
		return when (val collection = getLocalData(collectionId)) {
			is DomainAlbum -> refreshAlbum(collection.id)
			is DomainPlaylist -> refreshPlaylist(collection.id)
		}
	}

	fun getCollectionFlow(
		fullRefresh: Boolean,
		collectionId: String
	): Flow<UiState<DomainSongCollection>> = flow {
		val localData = getLocalData(collectionId)
		if (fullRefresh) {
			emit(UiState.Loading(data = localData))
			try {
				emit(UiState.Success(data = refreshLocalData(collectionId)))
			} catch (error: Exception) {
				emit(UiState.Error(error = error, data = localData))
			}
		} else {
			emit(UiState.Success(data = localData))
		}
	}.flowOn(Dispatchers.IO)

	fun getOtherAlbums(artistId: String, albumId: String) = albumDao
		.getAlbumsByArtistExcluding(artistId, albumId)
		.map { it.map { album -> album.toDomainModel() } }

	suspend fun getSongById(songId: String) = songDao
		.getSongById(songId)
		?.toDomainModel()

	suspend fun getAlbumInfo(albumId: String): ApiAlbumInfo {
		return sessionManager.api.getAlbumInfo(albumId)
	}
}
