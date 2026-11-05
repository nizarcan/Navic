/*
 * Navic, a Subsonic music streaming app for Android
 * Copyright (c) 2026 paige
 * SPDX-License-Identifier: GPL-3.0-only
 */

package paige.navic.domain.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import paige.navic.ui.core.PlayerUiState
import paige.navic.util.Logger

class PlayerStateRepository(
	private val preferences: DataStore<Preferences>
) {
	private val json = Json {
		ignoreUnknownKeys = true
	}

	val state: Flow<PlayerUiState> = preferences.data.map { decodeState(it[KEY_STATE]) }

	private fun decodeState(value: String?): PlayerUiState {
		if (value == null) return PlayerUiState()
		return try {
			json.decodeFromString<PlayerUiState>(value)
		} catch (ex: SerializationException) {
			Logger.e("PlayerStateRepository", "failed to deserialise state", ex)
			PlayerUiState()
		}
	}

	suspend fun updateState(transform: (PlayerUiState) -> PlayerUiState) {
		try {
			preferences.edit {
				it[KEY_STATE] = json.encodeToString(transform(decodeState(it[KEY_STATE])))
			}
		} catch (ex: CancellationException) {
			throw ex
		} catch (ex: Exception) {
			Logger.e("PlayerStateRepository", "failed to save state", ex)
		}
	}

	private companion object {
		val KEY_STATE = stringPreferencesKey("player_state")
	}
}
