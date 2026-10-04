/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.exoplayer

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import paige.navic.R
import paige.navic.data.database.dao.AlbumDao
import paige.navic.domain.manager.AndroidScrobbleManager
import paige.navic.domain.manager.AudioGainManager
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.EqualizerManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SyncManager
import paige.navic.domain.repository.PlayerStateRepository
import paige.navic.exoplayer.impl.ExoEqualizerManager

@UnstableApi
class ExoPlaybackService : MediaLibraryService(), KoinComponent {
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

	private val serviceScope = MainScope()

	private val stateHolder: ExoStateHolder by inject()

	private val connectivityManager: ConnectivityManager by inject()

	private val syncManager: SyncManager by inject()
	private val sessionManager: SessionManager by inject()
	private val preferenceManager: PreferenceManager by inject()
	private val equalizerManager: EqualizerManager by inject()
	private val playerStateRepository: PlayerStateRepository by inject()
	private val mediaItemFactory: MediaItemFactory by inject()
	private val albumDao: AlbumDao by inject()
	private val audioGainManager: AudioGainManager by inject()

	private lateinit var exoEqualizerManager: ExoEqualizerManager

	private lateinit var scrobbleManager: AndroidScrobbleManager

	override fun onCreate() {
		super.onCreate()
		exoEqualizerManager = ExoEqualizerManager(
			equalizerManager,
			this
		)
		stateHolder.initState(this, serviceScope)

		val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
			.build().apply {
				setSmallIcon(R.drawable.ic_navic)
			}

		setMediaNotificationProvider(notificationProvider)

		stateHolder.playerInstance.let { player ->
			player as Player

			PlaybackStateObserver(
				player,
				serviceScope,
				playerStateRepository,
				mediaItemFactory,
				albumDao,
				preferenceManager,
				audioGainManager
			).start()

			scrobbleManager = AndroidScrobbleManager(
				player,
				serviceScope,
				connectivityManager,
				syncManager,
				sessionManager,
				preferenceManager
			)

			player.addListener(exoEqualizerManager)

			exoEqualizerManager.apply {
				applyEqualizerMode(equalizerManager.config.value.mode)
				scope.launch(Dispatchers.Main) {
					equalizerManager.config.collect { updateEqualizer() }
				}
			}
		}
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
		return stateHolder.mediaSession
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
	}

	override fun onDestroy() {
		exoEqualizerManager.releaseEqualizer()
		scrobbleManager.release()
		scope.cancel()
		serviceScope.cancel()
		stopForeground(STOP_FOREGROUND_REMOVE)
		stateHolder.destroySession()
		super.onDestroy()
		stopSelf()
	}
}
