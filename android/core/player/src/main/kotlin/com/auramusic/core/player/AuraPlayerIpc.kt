package com.auramusic.core.player

import android.content.Context
import android.os.Bundle
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.SleepTimerMode
import com.auramusic.core.model.SleepTimerState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Contrato de comandos Binder (`MediaSession` `SessionCommand` / `SessionExtras`) para
 * sincronizar en tiempo real el ecualizador, los efectos Rust, el Crossfade/velocidad,
 * las preferencias de auriculares y el temporizador entre la interfaz principal y el
 * proceso aislado `:player`.
 */
object AuraPlayerIpcContract {
    const val ACTION_SYNC_EQUALIZER = "com.auramusic.action.SYNC_EQUALIZER"
    const val ACTION_SYNC_AUDIO_EFFECTS = "com.auramusic.action.SYNC_AUDIO_EFFECTS"
    const val ACTION_SYNC_PLAYBACK_TUNING = "com.auramusic.action.SYNC_PLAYBACK_TUNING"
    const val ACTION_SYNC_HEADPHONE_PREFS = "com.auramusic.action.SYNC_HEADPHONE_PREFS"

    private const val EXTRA_EQ_ENABLED = "eq_enabled"
    private const val EXTRA_EQ_BANDS = "eq_bands"
    private const val EXTRA_EQ_PRESET_ID = "eq_preset_id"

    private const val EXTRA_FX_REVERB_ENABLED = "fx_reverb_enabled"
    private const val EXTRA_FX_REVERB_ROOM = "fx_reverb_room"
    private const val EXTRA_FX_REVERB_DAMP = "fx_reverb_damp"
    private const val EXTRA_FX_REVERB_WET = "fx_reverb_wet"
    private const val EXTRA_FX_REVERB_WIDTH = "fx_reverb_width"
    private const val EXTRA_FX_SLOWED_ENABLED = "fx_slowed_enabled"
    private const val EXTRA_FX_SLOWED_SPEED = "fx_slowed_speed"
    private const val EXTRA_FX_SLOWED_PITCH = "fx_slowed_pitch"
    private const val EXTRA_FX_SLOWED_WARMTH = "fx_slowed_warmth"
    private const val EXTRA_FX_8D_ENABLED = "fx_8d_enabled"
    private const val EXTRA_FX_8D_SPEED = "fx_8d_speed"
    private const val EXTRA_FX_8D_DEPTH = "fx_8d_depth"
    private const val EXTRA_FX_CROSSFEED_ENABLED = "fx_crossfeed_enabled"
    private const val EXTRA_FX_CROSSFEED_STRENGTH = "fx_crossfeed_strength"
    private const val EXTRA_FX_CROSSFEED_ANGLE = "fx_crossfeed_angle"
    private const val EXTRA_FX_PRESET_ID = "fx_preset_id"

    private const val EXTRA_TUNING_CROSSFADE_ENABLED = "tuning_crossfade_enabled"
    private const val EXTRA_TUNING_CROSSFADE_SEC = "tuning_crossfade_sec"
    private const val EXTRA_TUNING_SPEED = "tuning_speed"
    private const val EXTRA_TUNING_PITCH = "tuning_pitch"
    private const val EXTRA_TUNING_LINK_PITCH = "tuning_link_pitch"

    private const val EXTRA_HP_AUTO_PAUSE = "hp_auto_pause"
    private const val EXTRA_HP_AUTO_RESUME = "hp_auto_resume"
    private const val EXTRA_HP_AUTO_DISABLE_8D = "hp_auto_disable_8d"
    private const val EXTRA_HP_AUTO_ENABLE_CF = "hp_auto_enable_cf"
    private const val EXTRA_HP_DOUBLE_TAP = "hp_double_tap"
    private const val EXTRA_HP_TRIPLE_TAP = "hp_triple_tap"
    private const val EXTRA_HP_PREV_THRESHOLD = "hp_prev_threshold"
    private const val EXTRA_HP_WRAP_QUEUE = "hp_wrap_queue"
    private const val EXTRA_HP_SEEK_STEP = "hp_seek_step"

    private const val EXTRA_SLEEP_MODE = "sleep_mode"
    private const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
    private const val EXTRA_SLEEP_TOTAL_MS = "sleep_total_ms"
    private const val EXTRA_SLEEP_REMAINING_MS = "sleep_remaining_ms"
    private const val EXTRA_SLEEP_FADE_OUT = "sleep_fade_out"
    private const val EXTRA_SLEEP_END_ELAPSED_MS = "sleep_end_elapsed_ms"

    val supportedActions = listOf(
        ACTION_SYNC_EQUALIZER,
        ACTION_SYNC_AUDIO_EFFECTS,
        ACTION_SYNC_PLAYBACK_TUNING,
        ACTION_SYNC_HEADPHONE_PREFS,
    )

    fun equalizerToBundle(state: EqualizerState): Bundle {
        val norm = state.normalized()
        return Bundle().apply {
            putBoolean(EXTRA_EQ_ENABLED, norm.isEnabled)
            putFloatArray(EXTRA_EQ_BANDS, norm.bandGainsDb.toFloatArray())
            putString(EXTRA_EQ_PRESET_ID, norm.activePresetId)
        }
    }

    fun equalizerFromBundle(bundle: Bundle): EqualizerState = EqualizerState(
        isEnabled = bundle.getBoolean(EXTRA_EQ_ENABLED, false),
        bandGainsDb = bundle.getFloatArray(EXTRA_EQ_BANDS)?.toList() ?: List(EqualizerState.BAND_COUNT) { 0f },
        activePresetId = bundle.getString(EXTRA_EQ_PRESET_ID) ?: EqualizerState.PRESET_FLAT_ID,
    ).normalized()

    fun audioEffectsToBundle(state: AudioEffectsState): Bundle {
        val norm = state.normalized()
        return Bundle().apply {
            putBoolean(EXTRA_FX_REVERB_ENABLED, norm.reverbEnabled)
            putFloat(EXTRA_FX_REVERB_ROOM, norm.reverbRoomSize)
            putFloat(EXTRA_FX_REVERB_DAMP, norm.reverbDamping)
            putFloat(EXTRA_FX_REVERB_WET, norm.reverbWetMix)
            putFloat(EXTRA_FX_REVERB_WIDTH, norm.reverbWidth)
            putBoolean(EXTRA_FX_SLOWED_ENABLED, norm.slowedEnabled)
            putFloat(EXTRA_FX_SLOWED_SPEED, norm.slowedSpeed)
            putBoolean(EXTRA_FX_SLOWED_PITCH, norm.slowedPreservePitch)
            putFloat(EXTRA_FX_SLOWED_WARMTH, norm.slowedWarmth)
            putBoolean(EXTRA_FX_8D_ENABLED, norm.spatial8dEnabled)
            putFloat(EXTRA_FX_8D_SPEED, norm.spatial8dSpeed)
            putFloat(EXTRA_FX_8D_DEPTH, norm.spatial8dDepth)
            putBoolean(EXTRA_FX_CROSSFEED_ENABLED, norm.crossfeedEnabled)
            putFloat(EXTRA_FX_CROSSFEED_STRENGTH, norm.crossfeedStrength)
            putFloat(EXTRA_FX_CROSSFEED_ANGLE, norm.crossfeedAngle)
            putString(EXTRA_FX_PRESET_ID, norm.activePresetId)
        }
    }

    fun audioEffectsFromBundle(bundle: Bundle): AudioEffectsState = AudioEffectsState(
        reverbEnabled = bundle.getBoolean(EXTRA_FX_REVERB_ENABLED, false),
        reverbRoomSize = bundle.getFloat(EXTRA_FX_REVERB_ROOM, AudioEffectsState.DEFAULT_ROOM_SIZE),
        reverbDamping = bundle.getFloat(EXTRA_FX_REVERB_DAMP, AudioEffectsState.DEFAULT_DAMPING),
        reverbWetMix = bundle.getFloat(EXTRA_FX_REVERB_WET, AudioEffectsState.DEFAULT_WET_MIX),
        reverbWidth = bundle.getFloat(EXTRA_FX_REVERB_WIDTH, AudioEffectsState.DEFAULT_WIDTH),
        slowedEnabled = bundle.getBoolean(EXTRA_FX_SLOWED_ENABLED, false),
        slowedSpeed = bundle.getFloat(EXTRA_FX_SLOWED_SPEED, AudioEffectsState.DEFAULT_SLOWED_SPEED),
        slowedPreservePitch = bundle.getBoolean(EXTRA_FX_SLOWED_PITCH, false),
        slowedWarmth = bundle.getFloat(EXTRA_FX_SLOWED_WARMTH, AudioEffectsState.DEFAULT_SLOWED_WARMTH),
        spatial8dEnabled = bundle.getBoolean(EXTRA_FX_8D_ENABLED, false),
        spatial8dSpeed = bundle.getFloat(EXTRA_FX_8D_SPEED, AudioEffectsState.DEFAULT_SPATIAL_8D_SPEED),
        spatial8dDepth = bundle.getFloat(EXTRA_FX_8D_DEPTH, AudioEffectsState.DEFAULT_SPATIAL_8D_DEPTH),
        crossfeedEnabled = bundle.getBoolean(EXTRA_FX_CROSSFEED_ENABLED, false),
        crossfeedStrength = bundle.getFloat(EXTRA_FX_CROSSFEED_STRENGTH, AudioEffectsState.DEFAULT_CROSSFEED_STRENGTH),
        crossfeedAngle = bundle.getFloat(EXTRA_FX_CROSSFEED_ANGLE, AudioEffectsState.DEFAULT_CROSSFEED_ANGLE),
        activePresetId = bundle.getString(EXTRA_FX_PRESET_ID) ?: AudioEffectsState.PRESET_OFF_ID,
    ).normalized()

    fun playbackTuningToBundle(tuning: PlaybackTuning): Bundle {
        val norm = tuning.normalized()
        return Bundle().apply {
            putBoolean(EXTRA_TUNING_CROSSFADE_ENABLED, norm.crossfadeEnabled)
            putInt(EXTRA_TUNING_CROSSFADE_SEC, norm.crossfadeSeconds)
            putFloat(EXTRA_TUNING_SPEED, norm.playbackSpeed)
            putFloat(EXTRA_TUNING_PITCH, norm.playbackPitch)
            putBoolean(EXTRA_TUNING_LINK_PITCH, norm.linkPitchToSpeed)
        }
    }

    fun playbackTuningFromBundle(bundle: Bundle): PlaybackTuning = PlaybackTuning(
        crossfadeEnabled = bundle.getBoolean(EXTRA_TUNING_CROSSFADE_ENABLED, false),
        crossfadeSeconds = bundle.getInt(EXTRA_TUNING_CROSSFADE_SEC, PlaybackTuning.DEFAULT_CROSSFADE_SECONDS),
        playbackSpeed = bundle.getFloat(EXTRA_TUNING_SPEED, PlaybackTuning.DEFAULT_PLAYBACK_SPEED),
        playbackPitch = bundle.getFloat(EXTRA_TUNING_PITCH, PlaybackTuning.DEFAULT_PLAYBACK_PITCH),
        linkPitchToSpeed = bundle.getBoolean(EXTRA_TUNING_LINK_PITCH, false),
    ).normalized()

    fun headphonePrefsToBundle(prefs: HeadphonePreferences): Bundle {
        val norm = prefs.normalized()
        return Bundle().apply {
            putBoolean(EXTRA_HP_AUTO_PAUSE, norm.autoPauseOnDisconnect)
            putBoolean(EXTRA_HP_AUTO_RESUME, norm.autoResumeOnConnect)
            putBoolean(EXTRA_HP_AUTO_DISABLE_8D, norm.autoDisable8dOnSpeaker)
            putBoolean(EXTRA_HP_AUTO_ENABLE_CF, norm.autoEnableCrossfeedOnHeadphones)
            putBoolean(EXTRA_HP_DOUBLE_TAP, norm.doubleTapNextEnabled)
            putBoolean(EXTRA_HP_TRIPLE_TAP, norm.tripleTapPreviousEnabled)
            putLong(EXTRA_HP_PREV_THRESHOLD, norm.previousRestartThresholdMs)
            putBoolean(EXTRA_HP_WRAP_QUEUE, norm.wrapQueueNavigation)
            putInt(EXTRA_HP_SEEK_STEP, norm.seekStepSeconds)
        }
    }

    fun headphonePrefsFromBundle(bundle: Bundle): HeadphonePreferences = HeadphonePreferences(
        autoPauseOnDisconnect = bundle.getBoolean(EXTRA_HP_AUTO_PAUSE, true),
        autoResumeOnConnect = bundle.getBoolean(EXTRA_HP_AUTO_RESUME, false),
        autoDisable8dOnSpeaker = bundle.getBoolean(EXTRA_HP_AUTO_DISABLE_8D, true),
        autoEnableCrossfeedOnHeadphones = bundle.getBoolean(EXTRA_HP_AUTO_ENABLE_CF, false),
        doubleTapNextEnabled = bundle.getBoolean(EXTRA_HP_DOUBLE_TAP, true),
        tripleTapPreviousEnabled = bundle.getBoolean(EXTRA_HP_TRIPLE_TAP, true),
        previousRestartThresholdMs = bundle.getLong(
            EXTRA_HP_PREV_THRESHOLD,
            HeadphonePreferences.DEFAULT_PREVIOUS_RESTART_MS,
        ),
        wrapQueueNavigation = bundle.getBoolean(EXTRA_HP_WRAP_QUEUE, true),
        seekStepSeconds = bundle.getInt(
            EXTRA_HP_SEEK_STEP,
            HeadphonePreferences.DEFAULT_SEEK_STEP_SECONDS,
        ),
    ).normalized()

    fun sleepTimerToBundle(state: SleepTimerState): Bundle = Bundle().apply {
        putString(EXTRA_SLEEP_MODE, state.mode.name)
        putInt(EXTRA_SLEEP_MINUTES, state.configuredMinutes)
        putLong(EXTRA_SLEEP_TOTAL_MS, state.totalDurationMs)
        putLong(EXTRA_SLEEP_REMAINING_MS, state.remainingMs)
        putBoolean(EXTRA_SLEEP_FADE_OUT, state.fadeOutOnFinish)
        putLong(EXTRA_SLEEP_END_ELAPSED_MS, state.endElapsedMs)
    }

    fun sleepTimerFromBundle(bundle: Bundle): SleepTimerState {
        val modeName = bundle.getString(EXTRA_SLEEP_MODE) ?: return SleepTimerState()
        val mode = runCatching { SleepTimerMode.valueOf(modeName) }.getOrDefault(SleepTimerMode.OFF)
        return SleepTimerState(
            mode = mode,
            configuredMinutes = bundle.getInt(EXTRA_SLEEP_MINUTES, 0),
            totalDurationMs = bundle.getLong(EXTRA_SLEEP_TOTAL_MS, 0L),
            remainingMs = bundle.getLong(EXTRA_SLEEP_REMAINING_MS, 0L),
            fadeOutOnFinish = bundle.getBoolean(EXTRA_SLEEP_FADE_OUT, true),
            endElapsedMs = bundle.getLong(EXTRA_SLEEP_END_ELAPSED_MS, 0L),
        )
    }
}

/**
 * Instantánea completa de preferencias de audio para arranque en frío del proceso `:player`.
 */
data class AuraPlayerConfigSnapshot(
    val equalizer: EqualizerState = EqualizerState(),
    val audioEffects: AudioEffectsState = AudioEffectsState(),
    val playbackTuning: PlaybackTuning = PlaybackTuning(),
    val headphonePreferences: HeadphonePreferences = HeadphonePreferences(),
)

/**
 * Almacén atómico sin bloqueos ni DataStore multi-proceso para que el proceso principal
 * escriba la configuración actual en `filesDir/aura_player_config.json` y el proceso `:player`
 * pueda leerla en frío (por ejemplo al reanudar desde Bluetooth sin UI abierta).
 */
object AuraPlayerConfigStore {
    private const val SNAPSHOT_FILE_NAME = "aura_player_config.json"

    @Synchronized
    fun writeSnapshot(context: Context, snapshot: AuraPlayerConfigSnapshot) {
        runCatching {
            val eq = snapshot.equalizer.normalized()
            val fx = snapshot.audioEffects.normalized()
            val tuning = snapshot.playbackTuning.normalized()
            val hp = snapshot.headphonePreferences.normalized()

            val root = JSONObject().apply {
                put(
                    "eq",
                    JSONObject().apply {
                        put("enabled", eq.isEnabled)
                        put("preset", eq.activePresetId)
                        put("bands", JSONArray().apply { eq.bandGainsDb.forEach { put(it.toDouble()) } })
                    },
                )
                put(
                    "fx",
                    JSONObject().apply {
                        put("reverbEnabled", fx.reverbEnabled)
                        put("reverbRoomSize", fx.reverbRoomSize.toDouble())
                        put("reverbDamping", fx.reverbDamping.toDouble())
                        put("reverbWetMix", fx.reverbWetMix.toDouble())
                        put("reverbWidth", fx.reverbWidth.toDouble())
                        put("slowedEnabled", fx.slowedEnabled)
                        put("slowedSpeed", fx.slowedSpeed.toDouble())
                        put("slowedPreservePitch", fx.slowedPreservePitch)
                        put("slowedWarmth", fx.slowedWarmth.toDouble())
                        put("spatial8dEnabled", fx.spatial8dEnabled)
                        put("spatial8dSpeed", fx.spatial8dSpeed.toDouble())
                        put("spatial8dDepth", fx.spatial8dDepth.toDouble())
                        put("crossfeedEnabled", fx.crossfeedEnabled)
                        put("crossfeedStrength", fx.crossfeedStrength.toDouble())
                        put("crossfeedAngle", fx.crossfeedAngle.toDouble())
                        put("presetId", fx.activePresetId)
                    },
                )
                put(
                    "tuning",
                    JSONObject().apply {
                        put("crossfadeEnabled", tuning.crossfadeEnabled)
                        put("crossfadeSeconds", tuning.crossfadeSeconds)
                        put("playbackSpeed", tuning.playbackSpeed.toDouble())
                        put("playbackPitch", tuning.playbackPitch.toDouble())
                        put("linkPitchToSpeed", tuning.linkPitchToSpeed)
                    },
                )
                put(
                    "hp",
                    JSONObject().apply {
                        put("autoPauseOnDisconnect", hp.autoPauseOnDisconnect)
                        put("autoResumeOnConnect", hp.autoResumeOnConnect)
                        put("autoDisable8dOnSpeaker", hp.autoDisable8dOnSpeaker)
                        put("autoEnableCrossfeedOnHeadphones", hp.autoEnableCrossfeedOnHeadphones)
                        put("doubleTapNextEnabled", hp.doubleTapNextEnabled)
                        put("tripleTapPreviousEnabled", hp.tripleTapPreviousEnabled)
                        put("previousRestartThresholdMs", hp.previousRestartThresholdMs)
                        put("wrapQueueNavigation", hp.wrapQueueNavigation)
                        put("seekStepSeconds", hp.seekStepSeconds)
                    },
                )
            }

            val target = File(context.filesDir, SNAPSHOT_FILE_NAME)
            val tmp = File(context.filesDir, "$SNAPSHOT_FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    fun readSnapshot(context: Context): AuraPlayerConfigSnapshot {
        return runCatching {
            val file = File(context.filesDir, SNAPSHOT_FILE_NAME)
            if (!file.exists()) return AuraPlayerConfigSnapshot()
            val root = JSONObject(file.readText())

            val eqJson = root.optJSONObject("eq")
            val eqBandsArr = eqJson?.optJSONArray("bands")
            val eqBands = if (eqBandsArr != null) {
                List(eqBandsArr.length()) { idx -> eqBandsArr.optDouble(idx, 0.0).toFloat() }
            } else {
                List(EqualizerState.BAND_COUNT) { 0f }
            }
            val eq = EqualizerState(
                isEnabled = eqJson?.optBoolean("enabled", false) ?: false,
                bandGainsDb = eqBands,
                activePresetId = eqJson?.optString("preset", EqualizerState.PRESET_FLAT_ID)
                    ?: EqualizerState.PRESET_FLAT_ID,
            ).normalized()

            val fxJson = root.optJSONObject("fx")
            val fx = if (fxJson != null) {
                AudioEffectsState(
                    reverbEnabled = fxJson.optBoolean("reverbEnabled", false),
                    reverbRoomSize = fxJson.optDouble("reverbRoomSize", AudioEffectsState.DEFAULT_ROOM_SIZE.toDouble()).toFloat(),
                    reverbDamping = fxJson.optDouble("reverbDamping", AudioEffectsState.DEFAULT_DAMPING.toDouble()).toFloat(),
                    reverbWetMix = fxJson.optDouble("reverbWetMix", AudioEffectsState.DEFAULT_WET_MIX.toDouble()).toFloat(),
                    reverbWidth = fxJson.optDouble("reverbWidth", AudioEffectsState.DEFAULT_WIDTH.toDouble()).toFloat(),
                    slowedEnabled = fxJson.optBoolean("slowedEnabled", false),
                    slowedSpeed = fxJson.optDouble("slowedSpeed", AudioEffectsState.DEFAULT_SLOWED_SPEED.toDouble()).toFloat(),
                    slowedPreservePitch = fxJson.optBoolean("slowedPreservePitch", false),
                    slowedWarmth = fxJson.optDouble("slowedWarmth", AudioEffectsState.DEFAULT_SLOWED_WARMTH.toDouble()).toFloat(),
                    spatial8dEnabled = fxJson.optBoolean("spatial8dEnabled", false),
                    spatial8dSpeed = fxJson.optDouble("spatial8dSpeed", AudioEffectsState.DEFAULT_SPATIAL_8D_SPEED.toDouble()).toFloat(),
                    spatial8dDepth = fxJson.optDouble("spatial8dDepth", AudioEffectsState.DEFAULT_SPATIAL_8D_DEPTH.toDouble()).toFloat(),
                    crossfeedEnabled = fxJson.optBoolean("crossfeedEnabled", false),
                    crossfeedStrength = fxJson.optDouble("crossfeedStrength", AudioEffectsState.DEFAULT_CROSSFEED_STRENGTH.toDouble()).toFloat(),
                    crossfeedAngle = fxJson.optDouble("crossfeedAngle", AudioEffectsState.DEFAULT_CROSSFEED_ANGLE.toDouble()).toFloat(),
                    activePresetId = fxJson.optString("presetId", AudioEffectsState.PRESET_OFF_ID),
                ).normalized()
            } else {
                AudioEffectsState()
            }

            val tuningJson = root.optJSONObject("tuning")
            val tuning = if (tuningJson != null) {
                PlaybackTuning(
                    crossfadeEnabled = tuningJson.optBoolean("crossfadeEnabled", false),
                    crossfadeSeconds = tuningJson.optInt("crossfadeSeconds", PlaybackTuning.DEFAULT_CROSSFADE_SECONDS),
                    playbackSpeed = tuningJson.optDouble("playbackSpeed", PlaybackTuning.DEFAULT_PLAYBACK_SPEED.toDouble()).toFloat(),
                    playbackPitch = tuningJson.optDouble("playbackPitch", PlaybackTuning.DEFAULT_PLAYBACK_PITCH.toDouble()).toFloat(),
                    linkPitchToSpeed = tuningJson.optBoolean("linkPitchToSpeed", false),
                ).normalized()
            } else {
                PlaybackTuning()
            }

            val hpJson = root.optJSONObject("hp")
            val hp = if (hpJson != null) {
                HeadphonePreferences(
                    autoPauseOnDisconnect = hpJson.optBoolean("autoPauseOnDisconnect", true),
                    autoResumeOnConnect = hpJson.optBoolean("autoResumeOnConnect", false),
                    autoDisable8dOnSpeaker = hpJson.optBoolean("autoDisable8dOnSpeaker", true),
                    autoEnableCrossfeedOnHeadphones = hpJson.optBoolean("autoEnableCrossfeedOnHeadphones", false),
                    doubleTapNextEnabled = hpJson.optBoolean("doubleTapNextEnabled", true),
                    tripleTapPreviousEnabled = hpJson.optBoolean("tripleTapPreviousEnabled", true),
                    previousRestartThresholdMs = hpJson.optLong("previousRestartThresholdMs", HeadphonePreferences.DEFAULT_PREVIOUS_RESTART_MS),
                    wrapQueueNavigation = hpJson.optBoolean("wrapQueueNavigation", true),
                    seekStepSeconds = hpJson.optInt("seekStepSeconds", HeadphonePreferences.DEFAULT_SEEK_STEP_SECONDS),
                ).normalized()
            } else {
                HeadphonePreferences()
            }

            AuraPlayerConfigSnapshot(
                equalizer = eq,
                audioEffects = fx,
                playbackTuning = tuning,
                headphonePreferences = hp,
            )
        }.getOrDefault(AuraPlayerConfigSnapshot())
    }
}
