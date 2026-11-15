package com.auramusic.app

import android.net.Uri

/**
 * Definición centralizada de rutas de navegación y reglas de visibilidad del chrome inferior
 * (MiniPlayer + NavigationBar) para Aura Music.
 */
internal object AuraRoutes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_VISUAL_FX = "settings/visual_fx"
    const val SETTINGS_HEADPHONES = "settings/headphones"
    const val SETTINGS_MEDIA_BUTTONS = "settings/media_buttons"
    const val SETTINGS_SYSTEM_FOLDER = "settings/system_folder"
    const val EQUALIZER = "equalizer"
    const val AUDIO_EFFECTS = "audio_effects"
    const val DOWNLOADER = "downloader"
    const val LIKED = "liked"
    const val PLAYER = "player"
    const val QUEUE = "queue"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val ARTIST_DETAIL = "artist/{artistKey}"

    fun playlistDetail(playlistId: Long): String = "playlist/$playlistId"

    fun artistDetail(artistKey: String): String = "artist/${Uri.encode(artistKey)}"

    /**
     * Indica si debe mostrarse la barra inferior compartida en la ruta actual.
     * Se oculta en vistas inmersivas como reproductor completo, ecualizador, efectos y descargador.
     */
    fun shouldShowBottomChrome(route: String?): Boolean =
        route != PLAYER &&
            route != EQUALIZER &&
            route != AUDIO_EFFECTS &&
            route != DOWNLOADER &&
            route != QUEUE

    fun isPlaylistsSection(route: String?): Boolean =
        route == PLAYLISTS ||
            route == LIKED ||
            route == PLAYLIST_DETAIL ||
            route == ARTIST_DETAIL

    fun isSettingsSection(route: String?): Boolean =
        route == SETTINGS ||
            route == SETTINGS_THEME ||
            route == SETTINGS_VISUAL_FX ||
            route == SETTINGS_HEADPHONES ||
            route == SETTINGS_MEDIA_BUTTONS ||
            route == SETTINGS_SYSTEM_FOLDER
}
