/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.playback.exoplayer

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import paige.navic.R
import paige.navic.database.dao.AlbumDao
import paige.navic.database.dao.ArtistDao
import paige.navic.database.dao.PlaylistDao
import paige.navic.database.dao.RadioDao
import paige.navic.database.dao.SongDao
import paige.navic.database.entity.AlbumEntity
import paige.navic.database.entity.PlaylistEntity
import paige.navic.database.mapper.toDomainModel
import paige.navic.database.mapper.toEntity
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.manager.session.SessionManager
import paige.navic.domain.manager.session.SessionState
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainArtist
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainRadio
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.ExplicitContentPlayback
import paige.navic.domain.repository.CollectionRepository
import paige.navic.domain.repository.PlayerStateRepository
import paige.navic.domain.repository.SearchRepository
import paige.navic.util.Logger
import java.text.Collator

/** Serves Android Auto's native media UI without requiring an Activity or ViewModel. */
@UnstableApi
internal class CarMediaLibraryCallback(
	private val context: Context,
	private val scope: CoroutineScope
) : MediaLibrarySession.Callback, KoinComponent {
	private val collectionRepository: CollectionRepository by inject()
	private val albumDao: AlbumDao by inject()
	private val songDao: SongDao by inject()
	private val artistDao: ArtistDao by inject()
	private val playlistDao: PlaylistDao by inject()
	private val radioDao: RadioDao by inject()
	private val searchRepository: SearchRepository by inject()
	private val sessionManager: SessionManager by inject()
	private val preferenceManager: PreferenceManager by inject()
	private val stateRepository: PlayerStateRepository by inject()
	private val itemFactory: MediaItemFactory by inject()

	// Keep the notified search count and subsequent pages consistent, bounded per controller.
	private val searches =
		mutableMapOf<MediaSession.ControllerInfo, Pair<String, List<MediaItem>>>()

	override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
		MediaSession.ConnectionResult.accept(
			MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
				.add(SessionCommand(ExoStateHolder.COMMAND_SHUFFLE, Bundle.EMPTY))
				.add(SessionCommand(ExoStateHolder.COMMAND_REPEAT, Bundle.EMPTY))
				.apply {
					if (controller.uid == Process.myUid()) {
						add(SessionCommand(ExoStateHolder.COMMAND_SET_SHUFFLE_ORDER, Bundle.EMPTY))
					}
				}
				.build(),
			MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
		)

	override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
		searches.remove(controller)
	}

	override fun onCustomCommand(
		session: MediaSession,
		controller: MediaSession.ControllerInfo,
		customCommand: SessionCommand,
		args: Bundle
	): ListenableFuture<SessionResult> {
		val player = session.player
		when (customCommand.customAction) {
			ExoStateHolder.COMMAND_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
			ExoStateHolder.COMMAND_REPEAT -> player.repeatMode = when (player.repeatMode) {
				Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
				Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
				else -> Player.REPEAT_MODE_OFF
			}
			ExoStateHolder.COMMAND_SET_SHUFFLE_ORDER -> {
				val order = args.getIntArray(ExoStateHolder.KEY_SHUFFLE_ORDER)
				if (controller.uid != Process.myUid() || order == null ||
					order.size != player.mediaItemCount || order.toSet().size != order.size ||
					order.any { it !in 0 until player.mediaItemCount }
				) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
				(player as ExoPlayer).setShuffleOrder(
					ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime())
				)
			}
			else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
		}
		return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
	}

	override fun onGetLibraryRoot(
		session: MediaLibrarySession,
		browser: MediaSession.ControllerInfo,
		params: LibraryParams?
	): ListenableFuture<LibraryResult<MediaItem>> {
		val extras = Bundle().apply {
			putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
			putInt(
				MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
				MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
			)
			putInt(
				MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
				MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
			)
		}
		return Futures.immediateFuture(
			LibraryResult.ofItem(
				folder(CarMediaIds.ROOT, context.getString(R.string.app_name)),
				LibraryParams.Builder().setExtras(extras).build()
			)
		)
	}

	override fun onGetChildren(
		session: MediaLibrarySession,
		browser: MediaSession.ControllerInfo,
		parentId: String,
		page: Int,
		pageSize: Int,
		params: LibraryParams?
	): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
		require(page >= 0 && pageSize > 0)
		val children = when (parentId) {
			CarMediaIds.ROOT -> listOf(
				CarMediaIds.RECENT,
				CarMediaIds.ALBUMS,
				CarMediaIds.ARTISTS,
				CarMediaIds.LIBRARY
			)
				.map { requireNotNull(catalogFolder(it)) }.carPage(page, pageSize)
			CarMediaIds.RECENT -> albumDao.observeRecentAlbums().first()
				.map(::albumItem).carPage(page, pageSize)
			CarMediaIds.ALBUMS -> albumDao.getBrowsableAlbums(pageSize, page.toLong() * pageSize)
				.map(::albumItem)
			CarMediaIds.ARTISTS -> alphabetical(
				artistDao.observeBrowsableArtists().first().map { artistItem(it.toDomainModel()) }
			).carPage(page, pageSize)
			CarMediaIds.LIBRARY -> listOf(CarMediaIds.PLAYLISTS, CarMediaIds.RADIOS)
				.map { requireNotNull(catalogFolder(it)) }.carPage(page, pageSize)
			CarMediaIds.PLAYLISTS -> alphabetical(
				playlistDao.observeBrowsablePlaylists().first().map(::playlistItem)
			).carPage(page, pageSize)
			CarMediaIds.RADIOS -> alphabetical(
				radioDao.observeBrowsableRadios().first().map { radioItem(it.toDomainModel()) }
			).carPage(page, pageSize)
			else -> {
				val id = requireNotNull(CarMediaIds.parse(parentId))
				when (id.type) {
					"album" -> collectionRepository.getAlbumSongs(id.id)
						.filter(::allowed).map { songItem(it, id.id) }.carPage(page, pageSize)
					"artist" -> alphabetical(
						albumDao.getBrowsableAlbumsByArtist(id.id).map(::albumItem)
					).carPage(page, pageSize)
					"playlist" -> collectionRepository.getPlaylistSongs(id.id)
						.mapIndexedNotNull { position, song ->
							song.takeIf(::allowed)?.let {
								songItem(it, playlistId = id.id, position = position)
							}
						}.carPage(page, pageSize)
					else -> throw IllegalArgumentException("Not a browsable item")
				}
			}
		}
		LibraryResult.ofItemList(children, params)
	}

	override fun onGetItem(
		session: MediaLibrarySession,
		browser: MediaSession.ControllerInfo,
		mediaId: String
	): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
		val item = catalogFolder(mediaId) ?: run {
			val id = requireNotNull(CarMediaIds.parse(mediaId))
			when (id.type) {
				"song" -> songDao.getSongById(id.id)?.toDomainModel()?.takeIf(::allowed)
					?.let { songItem(it, id.albumId, id.playlistId, id.position) }
				"album" -> albumDao.getAlbumById(id.id)?.album?.let(::albumItem)
				"artist" -> artistDao.getArtistById(id.id)?.toDomainModel()?.let(::artistItem)
				"playlist" -> playlistDao.getPlaylistById(id.id)?.playlist?.let(::playlistItem)
				"radio" -> radioDao.getRadioById(id.id)?.toDomainModel()?.let(::radioItem)
				else -> null
			}
		}
		LibraryResult.ofItem(requireNotNull(item), null)
	}

	override fun onSearch(
		session: MediaLibrarySession,
		browser: MediaSession.ControllerInfo,
		query: String,
		params: LibraryParams?
	): ListenableFuture<LibraryResult<Void>> = libraryFuture {
		val results = search(query)
		searches[browser] = query to results
		session.notifySearchResultChanged(browser, query, results.size, params)
		LibraryResult.ofVoid(params)
	}

	override fun onGetSearchResult(
		session: MediaLibrarySession,
		browser: MediaSession.ControllerInfo,
		query: String,
		page: Int,
		pageSize: Int,
		params: LibraryParams?
	): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
		val results = searches[browser]?.takeIf { it.first == query }?.second ?: search(query)
		LibraryResult.ofItemList(results.carPage(page, pageSize), params)
	}

	private suspend fun search(query: String): List<MediaItem> {
		if (query.isBlank()) return emptyList()
		val results = searchRepository.search(query.trim())
		return (results.filterIsInstance<DomainSong>().filter(::allowed).map { songItem(it) }
			+ results.filterIsInstance<DomainAlbum>().map { albumItem(it.toEntity()) }
			+ results.filterIsInstance<DomainArtist>().map(::artistItem)).take(100)
	}

	override fun onAddMediaItems(
		mediaSession: MediaSession,
		controller: MediaSession.ControllerInfo,
		mediaItems: List<MediaItem>
	): ListenableFuture<List<MediaItem>> = future {
		if (controller.uid == Process.myUid() &&
			mediaItems.all { it.localConfiguration != null }
		) {
			mediaItems
		} else {
			check(authenticated())
			mediaItems.flatMap { resolve(it) }.filter(::allowed).map { itemFactory.create(it) }
		}
	}

	override fun onSetMediaItems(
		mediaSession: MediaSession,
		controller: MediaSession.ControllerInfo,
		mediaItems: List<MediaItem>,
		startIndex: Int,
		startPositionMs: Long
	): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
		// Preserve the phone's queue and radio URLs; external clients resolve catalog IDs only.
		if (controller.uid == Process.myUid() &&
			mediaItems.all { it.localConfiguration != null }
		) {
			return@future MediaSession.MediaItemsWithStartPosition(
				mediaItems,
				startIndex,
				startPositionMs
			)
		}
		check(authenticated())
		val selected = mediaItems.getOrNull(startIndex.takeIf { it != C.INDEX_UNSET } ?: 0)
		val id = selected?.mediaId?.let(CarMediaIds::parse)
		val songs = when {
			mediaItems.size == 1 && id?.type == "song" && id.playlistId != null ->
				collectionRepository.getPlaylistSongs(id.playlistId)
			mediaItems.size == 1 && id?.type == "song" && id.albumId != null ->
				collectionRepository.getAlbumSongs(id.albumId)
			else -> mediaItems.flatMap { resolve(it) }
		}
		val entries = songs.withIndex().filter { allowed(it.value) }
		require(entries.isNotEmpty()) { "No playable results" }
		val selectedId = id?.id ?: selected?.mediaId
		// A playlist can contain the same song more than once. Preserve the selected occurrence.
		val exactIndex = entries.indexOfFirst {
			it.value.id == selectedId &&
				(id?.playlistId == null || id.position == null || it.index == id.position)
		}
		val index = if (exactIndex >= 0) exactIndex else {
			entries.indexOfFirst { it.value.id == selectedId }.coerceAtLeast(0)
		}
		val playable = entries.map { it.value }
		MediaSession.MediaItemsWithStartPosition(
			playable.map { itemFactory.create(it) },
			index,
			startPositionMs
		)
	}

	private suspend fun resolve(item: MediaItem): List<DomainSong> {
		item.requestMetadata.searchQuery?.let { query ->
			if (query.isBlank()) return recentSongs()
			val results = searchRepository.search(query.trim())
			val songs = results.filterIsInstance<DomainSong>().filter(::allowed)
			if (songs.isNotEmpty()) return songs
			results.filterIsInstance<DomainAlbum>().firstOrNull()?.let {
				return collectionRepository.getAlbumSongs(it.id)
			}
			results.filterIsInstance<DomainArtist>().firstOrNull()?.let {
				return songDao.getSongsByArtistId(it.id).map { song -> song.toDomainModel() }
			}
			return emptyList()
		}
		val id = CarMediaIds.parse(item.mediaId)
		return when (id?.type) {
			"album" -> collectionRepository.getAlbumSongs(id.id)
			"artist" -> songDao.getSongsByArtistId(id.id).map { it.toDomainModel() }
			"playlist" -> collectionRepository.getPlaylistSongs(id.id)
			"radio" -> listOfNotNull(
				radioDao.getRadioById(id.id)?.toDomainModel()?.toPlaybackSong()
			)
			else -> listOfNotNull(songDao.getSongById(id?.id ?: item.mediaId)?.toDomainModel())
		}
	}

	override fun onPlaybackResumption(
		mediaSession: MediaSession,
		controller: MediaSession.ControllerInfo,
		isForPlayback: Boolean
	): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
		check(authenticated())
		val saved = stateRepository.state.first()
		val songs = saved.queue.filter(::allowed).ifEmpty { recentSongs().filter(::allowed) }
		require(songs.isNotEmpty()) { "No recent playback" }
		val index = saved.currentIndex.takeIf { songs.getOrNull(it)?.id == saved.currentSong?.id }
			?: songs.indexOfFirst { it.id == saved.currentSong?.id }.coerceAtLeast(0)
		val position = if (songs[index].id == saved.currentSong?.id) {
			(saved.progress * songs[index].duration.inWholeMilliseconds).toLong().coerceAtLeast(0)
		} else 0L
		if (isForPlayback) {
			val player = mediaSession.player as ExoPlayer
			// Media3 installs the returned queue after this callback completes.
			player.addListener(object : Player.Listener {
				override fun onTimelineChanged(timeline: Timeline, reason: Int) {
					player.removeListener(this)
					val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
					if (ids != songs.map { it.id }) return
					val order = saved.shuffleOrder
					if (songs == saved.queue && order.size == songs.size &&
						order.toSet() == songs.indices.toSet()
					) {
						player.setShuffleOrder(
							ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime())
						)
					}
					player.shuffleModeEnabled = saved.isShuffleEnabled
					player.repeatMode = saved.repeatMode
					player.playbackParameters = PlaybackParameters(
						saved.playbackSpeed,
						if (saved.preservePitch) 1f else saved.playbackSpeed
					)
				}
			})
		}
		MediaSession.MediaItemsWithStartPosition(
			songs.map { itemFactory.create(it) },
			index,
			position
		)
	}

	private suspend fun recentSongs(): List<DomainSong> = albumDao.observeRecentAlbums().first()
		.firstOrNull()?.let { collectionRepository.getAlbumSongs(it.albumId) }.orEmpty()

	/** Return a complete, title-sorted native list so the host can offer its A–Z index. */
	private fun alphabetical(items: List<MediaItem>): List<MediaItem> {
		val collator = Collator.getInstance(context.resources.configuration.locales[0])
		return items.sortedWith { a, b ->
			collator.compare(a.mediaMetadata.title.toString(), b.mediaMetadata.title.toString())
				.takeIf { it != 0 } ?: a.mediaId.compareTo(b.mediaId)
		}
	}

	private fun allowed(song: DomainSong) = song.explicitStatus != DomainExplicitStatus.Explicit ||
		preferenceManager.explicitContentPlayback == ExplicitContentPlayback.Allowed

	private fun catalogFolder(id: String): MediaItem? = when (id) {
		CarMediaIds.ROOT -> folder(id, context.getString(R.string.app_name))
		CarMediaIds.RECENT -> folder(
			id, context.getString(R.string.car_recent), R.drawable.ic_car_recent
		)
		CarMediaIds.ALBUMS -> folder(
			id, context.getString(R.string.title_albums), R.drawable.ic_car_albums
		)
		CarMediaIds.ARTISTS -> folder(
			id, context.getString(R.string.title_artists), R.drawable.ic_car_artists, true
		)
		CarMediaIds.LIBRARY -> folder(
			id, context.getString(R.string.title_library), R.drawable.ic_car_library, true
		)
		CarMediaIds.PLAYLISTS -> folder(
			id, context.getString(R.string.title_playlists), R.drawable.ic_car_playlists, true
		)
		CarMediaIds.RADIOS -> folder(
			id, context.getString(R.string.title_radios), R.drawable.ic_car_radio, true
		)
		else -> null
	}

	private fun listStyles() = Bundle().apply {
		putInt(
			MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
			MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
		)
		putInt(
			MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
			MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
		)
	}

	private fun folder(
		id: String,
		title: String,
		icon: Int? = null,
		listChildren: Boolean = false
	) = MediaItem.Builder()
		.setMediaId(id)
		.setMediaMetadata(
			MediaMetadata.Builder()
				.setTitle(title)
				.setIsBrowsable(true)
				.setIsPlayable(false)
				.setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
				.setExtras(if (listChildren) listStyles() else null)
				.setArtworkUri(icon?.let {
					"android.resource://${context.packageName}/$it".toUri()
				})
				.build()
		)
		.build()

	private fun albumItem(album: AlbumEntity) = MediaItem.Builder()
		.setMediaId(CarMediaIds.item("album", album.albumId))
		.setMediaMetadata(
			MediaMetadata.Builder()
				.setTitle(album.name ?: context.getString(R.string.info_unknown))
				.setSubtitle(album.artistName)
				.setArtist(album.artistName)
				.setIsBrowsable(true)
				.setIsPlayable(false)
				.setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
				.setArtworkUri(album.coverArtId?.let { sessionManager.getCoverArtUrl(it).toUri() })
				.build()
		)
		.build()

	private fun artistItem(artist: DomainArtist) = MediaItem.Builder()
		.setMediaId(CarMediaIds.item("artist", artist.id))
		.setMediaMetadata(
			MediaMetadata.Builder()
				.setTitle(artist.name)
				.setIsBrowsable(true)
				.setIsPlayable(false)
				.setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
				.setExtras(listStyles())
				.setArtworkUri(artist.coverArtId?.let { sessionManager.getCoverArtUrl(it).toUri() })
				.build()
		)
		.build()

	private fun playlistItem(playlist: PlaylistEntity) = MediaItem.Builder()
		.setMediaId(CarMediaIds.item("playlist", playlist.playlistId))
		.setMediaMetadata(
			MediaMetadata.Builder()
				.setTitle(playlist.name ?: context.getString(R.string.car_unnamed_playlist))
				.setSubtitle(playlist.owner)
				.setIsBrowsable(true)
				.setIsPlayable(false)
				.setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
				.setExtras(listStyles())
				.setArtworkUri(playlist.coverArtId?.let {
					sessionManager.getCoverArtUrl(it).toUri()
				})
				.build()
		)
		.build()

	private fun radioItem(radio: DomainRadio) = MediaItem.Builder()
		.setMediaId(CarMediaIds.item("radio", radio.id))
		.setMediaMetadata(
			itemFactory.metadata(radio.toPlaybackSong()).buildUpon()
				.setArtworkUri(
					radio.coverArtId?.let { sessionManager.getCoverArtUrl(it).toUri() }
						?: "android.resource://${context.packageName}/${R.drawable.ic_car_radio}".toUri()
				)
				.build()
		)
		.build()

	private fun songItem(
		song: DomainSong,
		albumId: String? = null,
		playlistId: String? = null,
		position: Int? = null
	) = MediaItem.Builder()
		.setMediaId(CarMediaIds.item("song", song.id, albumId, playlistId, position))
		.setMediaMetadata(itemFactory.metadata(song))
		.build()

	fun attach(session: MediaLibrarySession) {
		scope.launch {
			albumDao.observeRecentAlbums().collect {
				session.notifyChildrenChanged(CarMediaIds.RECENT, it.size, null)
			}
		}
		scope.launch {
			artistDao.observeBrowsableArtists().collect {
				session.notifyChildrenChanged(CarMediaIds.ARTISTS, it.size, null)
			}
		}
		scope.launch {
			playlistDao.observeBrowsablePlaylists().collect {
				session.notifyChildrenChanged(CarMediaIds.PLAYLISTS, it.size, null)
			}
		}
		scope.launch {
			radioDao.observeBrowsableRadios().collect {
				session.notifyChildrenChanged(CarMediaIds.RADIOS, it.size, null)
			}
		}
	}

	private suspend fun authenticated(): Boolean = sessionManager.sessionState
		.first { it !is SessionState.Restoring } is SessionState.Authenticated

	private fun <T : Any> libraryFuture(
		block: suspend () -> LibraryResult<T>
	): ListenableFuture<LibraryResult<T>> = future {
		if (!authenticated()) {
			LibraryResult.ofError<T>(
				SessionError(
					SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
					context.getString(R.string.car_sign_in)
				)
			)
		} else try {
			block()
		} catch (e: CancellationException) {
			throw e
		} catch (e: IllegalArgumentException) {
			LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)
		} catch (e: Exception) {
			Logger.e("CarMediaLibrary", "Unable to load library", e)
			LibraryResult.ofError<T>(
				SessionError(SessionError.ERROR_IO, context.getString(R.string.car_load_error))
			)
		}
	}

	private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
		val result = SettableFuture.create<T>()
		val job = scope.launch {
			try {
				result.set(withTimeout(20_000) { block() })
			} catch (e: CancellationException) {
				result.cancel(false)
			} catch (e: Exception) {
				result.setException(e)
			}
		}
		job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
		result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
		return result
	}
}
