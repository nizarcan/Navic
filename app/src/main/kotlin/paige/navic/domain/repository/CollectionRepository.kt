/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import paige.navic.database.dao.AlbumDao
import paige.navic.database.dao.PlaylistDao
import paige.navic.database.dao.SongDao
import paige.navic.database.mapper.toDomainModel
import paige.navic.database.mapper.toEntity
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.connectivity.ReachabilityFlow
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainPlaylist
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import dev.zt64.subsonic.api.model.AlbumInfo as ApiAlbumInfo

class CollectionRepository(
	private val albumDao: AlbumDao,
	private val playlistDao: PlaylistDao,
	private val songDao: SongDao,
	private val dbRepository: DbRepository,
	private val sessionManager: SessionManager,
	private val reachability: ReachabilityFlow
) {
	suspend fun getLocalData(collectionId: String): DomainSongCollection {
		return albumDao.getAlbumById(collectionId)?.toDomainModel()
			?: playlistDao.getPlaylistById(collectionId)?.toDomainModel()
			?: error("Collection ID $collectionId is neither a known album or playlist")
	}

	suspend fun getAlbumSongs(id: String): List<DomainSong> {
		val album = albumDao.getAlbumById(id)
		val songs = album?.songs.orEmpty().map { it.toDomainModel() }
		val incomplete = songs.isEmpty() || songs.size < (album?.album?.songCount ?: 0)
		val available = if (incomplete && reachability.isReachable.value) {
			refreshAlbum(id).songs
		} else songs
		return available.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title }))
	}

	suspend fun getPlaylistSongs(id: String): List<DomainSong> {
		val playlist = requireNotNull(playlistDao.getPlaylistById(id)).toDomainModel()
		return if (playlist.songs.size < playlist.songCount && reachability.isReachable.value) {
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

	suspend fun getCollection(
		fullRefresh: Boolean,
		collectionId: String
	): DomainSongCollection {
		val localData = getLocalData(collectionId)
		return if (fullRefresh) {
			refreshLocalData(collectionId)
		} else {
			localData
		}
	}

	fun getOtherAlbums(artistId: String, albumId: String) = albumDao
		.getAlbumsByArtistExcluding(artistId, albumId)
		.map { it.map { album -> album.toDomainModel() } }

	suspend fun getSongById(songId: String) = songDao
		.getSongById(songId)
		?.toDomainModel()

	suspend fun getAlbumInfo(albumId: String): ApiAlbumInfo {
		return sessionManager.api.getAlbumInfoID3(albumId)
	}

	suspend fun removeSongFromPlaylist(playlistId: String, position: Int) {
		sessionManager.api.updatePlaylist(
			id = playlistId,
			songIndicesToRemove = listOf(position)
		)
	}

	fun getPagedPlaylistSongs(playlistId: String): Flow<PagingData<DomainSong>> {
		return Pager(
			config = PagingConfig(
				pageSize = 20,
				prefetchDistance = 10
			),
			pagingSourceFactory = {
				songDao.getPagedSongsByPlaylistId(playlistId)
			}
		).flow.map { pagingData -> pagingData.map { it.toDomainModel() } }
	}
}
