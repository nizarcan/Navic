/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.model.DomainAlbum
import paige.navic.domain.model.DomainExplicitStatus
import paige.navic.domain.model.DomainRadio
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.DomainSongCollection
import paige.navic.domain.model.settings.ExplicitContentPlayback
import paige.navic.domain.repository.PlayerStateRepository
import paige.navic.domain.repository.SongRepository
import paige.navic.ui.core.PlayerUiState

abstract class MediaPlayerViewModel(
	private val stateRepository: PlayerStateRepository,
	protected val songRepository: SongRepository,
	protected val connectivityManager: ConnectivityManager,
	protected val downloadManager: DownloadManager,
	protected val preferenceManager: PreferenceManager
) : ViewModel() {

	@Suppress("PropertyName")
	protected val _uiState = MutableStateFlow(PlayerUiState())
	val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

	protected fun isExplicit(song: DomainSong): Boolean {
		return song.explicitStatus == DomainExplicitStatus.Explicit
			&& preferenceManager.explicitContentPlayback != ExplicitContentPlayback.Allowed
	}

	init {
		viewModelScope.launch {
			restoreState()
		}
	}

	abstract fun addToQueueSingle(song: DomainSong, notify: Boolean = true)
	abstract fun addToQueue(collection: DomainSongCollection, notify: Boolean = true)
	abstract fun addToQueue(songs: List<DomainSong>, notify: Boolean = true)
	abstract fun removeFromQueue(index: Int)
	abstract fun moveQueueItem(fromIndex: Int, toIndex: Int)
	abstract fun clearQueue()
	abstract fun playAt(index: Int)
	abstract fun playNextSingle(song: DomainSong)
	abstract fun playNext(collection: DomainSongCollection)
	abstract fun playRadio(radio: DomainRadio)
	abstract fun pause()
	abstract fun resume()
	abstract fun seek(normalized: Float)
	abstract fun next()
	abstract fun previous()
	abstract fun toggleShuffle()
	abstract fun toggleRepeat()
	abstract fun shufflePlay(collection: DomainSongCollection)
	abstract fun setPlaybackSpeed(value: Float)

	fun playNow(song: DomainSong) = playNow(listOf(song))

	fun playNow(collection: DomainSongCollection, startIndex: Int = 0) {
		val songs = if (collection is DomainAlbum) {
			collection.songs.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
		} else {
			collection.songs
		}
		playNow(songs, startIndex)
	}

	abstract fun playNow(songs: List<DomainSong>, startIndex: Int = 0)

	fun togglePlay() {
		if (!uiState.value.isPaused) {
			pause()
		} else {
			resume()
		}
	}

	abstract fun syncPlayerWithState(state: PlayerUiState)

	protected fun checkAndAutoFillQueue() {
		if (!preferenceManager.autoFillQueue) return

		val state = uiState.value
		if (state.queue.isEmpty() || state.currentSong?.id?.startsWith("radio_") == true) return

		val remainingCount = state.queue.size - state.currentIndex

		if (remainingCount <= 1) {
			viewModelScope.launch {
				val randomSongs = songRepository.getRandomSongs(1)
				addToQueue(randomSongs, notify = false)
			}
		}
	}

	private suspend fun restoreState() {
		val savedState = stateRepository.state.first().copy(isPaused = true, isLoading = false)
		_uiState.value = savedState
		syncPlayerWithState(savedState)
	}
}
