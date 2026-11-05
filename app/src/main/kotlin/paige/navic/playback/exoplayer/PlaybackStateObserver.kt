/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.playback.exoplayer

import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import paige.navic.database.dao.AlbumDao
import paige.navic.domain.manager.playback.AudioGainManager
import paige.navic.domain.manager.runtime.PreferenceManager
import paige.navic.domain.model.DomainSong
import paige.navic.domain.model.settings.ReplayGainMode
import paige.navic.domain.repository.PlayerStateRepository
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The service owns saved state and gain, whether playback starts on the phone or in the car. */
@UnstableApi
internal class PlaybackStateObserver(
	private val player: Player,
	private val scope: CoroutineScope,
	private val stateRepository: PlayerStateRepository,
	private val itemFactory: MediaItemFactory,
	private val albumDao: AlbumDao,
	private val preferenceManager: PreferenceManager,
	private val audioGainManager: AudioGainManager
) : Player.Listener {
	private val changes = Channel<Unit>(Channel.CONFLATED)
	private var queue = emptyList<DomainSong>()
	private var hasQueue = false
	private var lastPlayedId: String? = null

	fun start() {
		player.addListener(this)
		scope.launch {
			queue = stateRepository.state.first().queue
			for (change in changes) {
				try {
					saveState()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Logger.e("PlaybackState", "Unable to save playback state", e)
				}
			}
		}
		scope.launch {
			snapshotFlow {
				Triple(
					preferenceManager.replayGainMode,
					preferenceManager.rgAmpGain,
					preferenceManager.ampGain
				)
			}.collect { applyAudioGain() }
		}
		scope.launch {
			while (true) {
				delay(2.seconds)
				if (player.isPlaying) changes.trySend(Unit)
			}
		}
	}

	override fun onEvents(player: Player, events: Player.Events) {
		// An initially empty service must not erase the queue waiting to be resumed.
		if (player.mediaItemCount > 0) hasQueue = true
		if (hasQueue) changes.trySend(Unit)
	}

	private fun queueIds() = (0 until player.mediaItemCount).map {
		player.getMediaItemAt(it).mediaId
	}

	private suspend fun saveState() {
		val ids = queueIds()
		if (ids != queue.map { it.id }) {
			val known = queue.associateBy { it.id }
			val loaded = itemFactory.resolveSongs(ids).associateBy { it.id }
			val songs = ids.mapNotNull { loaded[it] ?: known[it] }
			if (ids != queueIds() || songs.size != ids.size) return
			queue = songs
			if (queue.isEmpty()) lastPlayedId = null
		}
		applyAudioGain()
		val index = if (queue.isEmpty()) -1 else player.currentMediaItemIndex
		val current = queue.getOrNull(index)
		val duration = current?.duration?.inWholeMilliseconds ?: 0L
		val state = PlayerUiState(
			queue = queue,
			currentSong = current,
			currentIndex = index,
			isPaused = queue.isEmpty() || !player.playWhenReady ||
				player.playbackState == Player.STATE_ENDED,
			isShuffleEnabled = player.shuffleModeEnabled,
			shuffleOrder = player.currentShuffleOrder(),
			repeatMode = player.repeatMode,
			playbackSpeed = player.playbackParameters.speed,
			progress = if (duration > 0) {
				(player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
			} else 0f
		)
		val played = current?.takeIf { player.isPlaying && it.id != lastPlayedId }
		stateRepository.updateState {
			// Pitch preservation is a preference, ambiguous in Player when speed is 1x.
			state.copy(preservePitch = it.preservePitch)
		}
		if (played != null) {
			lastPlayedId = played.id
			played.albumId?.let { albumDao.markPlayed(it, Clock.System.now()) }
		}
	}

	private fun applyAudioGain() = audioGainManager.apply {
		setAmplifierValues(preferenceManager.rgAmpGain, preferenceManager.ampGain)
		val current = queue.getOrNull(player.currentMediaItemIndex)
		setReplayGainMetadata(current?.replayGain)
		val mode = when (val mode = preferenceManager.replayGainMode) {
			ReplayGainMode.Dynamic -> if (
				current?.albumId != null && queue.all { it.albumId == current.albumId }
			) ReplayGainMode.Album else ReplayGainMode.Track
			else -> mode
		}
		applyGainMode(mode)
	}
}

@UnstableApi
internal fun Player.currentShuffleOrder(): List<Int> {
	if (!shuffleModeEnabled) return emptyList()
	val timeline = currentTimeline
	return buildList {
		var index = timeline.getFirstWindowIndex(true)
		while (index != C.INDEX_UNSET) {
			add(index)
			index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
		}
	}
}
