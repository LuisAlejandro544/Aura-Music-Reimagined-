package com.auramusic.core.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * Envoltorio de [ForwardingPlayer] exclusivamente para la [androidx.media3.session.MediaSession]
 * del sistema Android. Garantiza que los comandos de pista anterior, siguiente y salto rápido AVRCP
 * estén siempre expuestos con el umbral de reinicio, navegación circular y paso de segundos configurados,
 * sin alterar la lógica interna de Crossfade sobre `primaryPlayer`.
 */
@OptIn(UnstableApi::class)
internal class SessionControlForwardingPlayer(
    private val wrapped: ExoPlayer,
    private val headphonePrefsProvider: () -> HeadphonePreferences,
) : ForwardingPlayer(wrapped) {
    private val listenerWrappers = IdentityHashMap<Player.Listener, Player.Listener>()

    private fun extendCommands(base: Player.Commands): Player.Commands {
        if (wrapped.mediaItemCount <= 0) return base
        return base.buildUpon()
            .addAll(
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_BACK,
            )
            .build()
    }

    override fun getAvailableCommands(): Player.Commands = extendCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        when (command) {
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_FORWARD,
            COMMAND_SEEK_BACK,
            -> wrapped.mediaItemCount > 0 || super.isCommandAvailable(command)
            else -> super.isCommandAvailable(command)
        }

    override fun addListener(listener: Player.Listener) {
        val wrapper = synchronized(listenerWrappers) {
            listenerWrappers.getOrPut(listener) {
                ExtendedCommandsListener.createProxy(listener, ::extendCommands)
            }
        }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(listenerWrappers) {
            listenerWrappers.remove(listener)
        }
        super.removeListener(wrapper ?: listener)
    }

    override fun getSeekForwardIncrement(): Long =
        HeadphoneRoutingHelper.clampSeekStepSeconds(
            headphonePrefsProvider().seekStepSeconds,
        ) * 1000L

    override fun getSeekBackIncrement(): Long =
        HeadphoneRoutingHelper.clampSeekStepSeconds(
            headphonePrefsProvider().seekStepSeconds,
        ) * 1000L

    override fun seekForward() {
        val incrementMs = getSeekForwardIncrement()
        val duration = wrapped.duration
        val target = if (duration > 0L) {
            (wrapped.currentPosition + incrementMs).coerceAtMost(duration)
        } else {
            wrapped.currentPosition + incrementMs
        }
        wrapped.seekTo(target)
    }

    override fun seekBack() {
        val incrementMs = getSeekBackIncrement()
        val target = (wrapped.currentPosition - incrementMs).coerceAtLeast(0L)
        wrapped.seekTo(target)
    }

    override fun seekToNext() {
        if (wrapped.hasNextMediaItem()) {
            super.seekToNext()
        } else if (headphonePrefsProvider().wrapQueueNavigation && wrapped.mediaItemCount > 0) {
            wrapped.seekTo(0, 0L)
            wrapped.play()
        }
    }

    override fun seekToNextMediaItem() {
        if (wrapped.hasNextMediaItem()) {
            super.seekToNextMediaItem()
        } else if (headphonePrefsProvider().wrapQueueNavigation && wrapped.mediaItemCount > 0) {
            wrapped.seekTo(0, 0L)
            wrapped.play()
        }
    }

    override fun seekToPrevious() {
        val prefs = headphonePrefsProvider()
        val thresholdMs = HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(
            prefs.previousRestartThresholdMs,
        )
        if (thresholdMs > 0L && wrapped.currentPosition > thresholdMs) {
            wrapped.seekTo(0L)
        } else if (wrapped.hasPreviousMediaItem()) {
            super.seekToPrevious()
        } else if (prefs.wrapQueueNavigation && wrapped.mediaItemCount > 1) {
            wrapped.seekTo(wrapped.mediaItemCount - 1, 0L)
            wrapped.play()
        } else if (wrapped.mediaItemCount > 0) {
            wrapped.seekTo(0L)
        }
    }

    override fun seekToPreviousMediaItem() {
        val prefs = headphonePrefsProvider()
        val thresholdMs = HeadphoneRoutingHelper.clampPreviousRestartThresholdMs(
            prefs.previousRestartThresholdMs,
        )
        if (thresholdMs > 0L && wrapped.currentPosition > thresholdMs) {
            wrapped.seekTo(0L)
        } else if (wrapped.hasPreviousMediaItem()) {
            super.seekToPreviousMediaItem()
        } else if (prefs.wrapQueueNavigation && wrapped.mediaItemCount > 1) {
            wrapped.seekTo(wrapped.mediaItemCount - 1, 0L)
            wrapped.play()
        } else if (wrapped.mediaItemCount > 0) {
            wrapped.seekTo(0L)
        }
    }
}

/**
 * Proxy dinámico de [Player.Listener] para que MediaSession reciba TODOS los callbacks
 * del reproductor (`onPlaybackStateChanged`, `onIsPlayingChanged`, `onEvents`, etc.) además
 * de extender `onAvailableCommandsChanged` con los botones Anterior/Siguiente.
 *
 * Nota técnica: se usa [Proxy] en lugar de la delegación de interfaz de Kotlin (`by`)
 * sobre `Player.Listener` porque Kotlin no delega los métodos `default` de Java (KT-18324),
 * lo que provocaba que MediaSession y el MiniPlayer quedaran congelados en `STATE_BUFFERING`.
 */
internal class ExtendedCommandsListener(
    private val delegate: Player.Listener,
    private val extend: (Player.Commands) -> Player.Commands,
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "equals" -> {
                    val other = args?.firstOrNull()
                    if (other === proxy) {
                        true
                    } else if (other != null && Proxy.isProxyClass(other.javaClass)) {
                        val otherHandler = Proxy.getInvocationHandler(other) as? ExtendedCommandsListener
                        otherHandler?.delegate == delegate
                    } else {
                        delegate == other
                    }
                }
                "hashCode" -> delegate.hashCode()
                "toString" -> delegate.toString()
                else -> if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)
            }
        }

        // Intercepta `onAvailableCommandsChanged` (único callback con parámetro `Player.Commands`)
        // de forma segura tanto en Debug como en Release con R8.
        val paramTypes = method.parameterTypes
        if (method.name == "onAvailableCommandsChanged" ||
            (paramTypes.size == 1 && paramTypes[0] == Player.Commands::class.java)
        ) {
            val commands = args?.firstOrNull() as? Player.Commands
            if (commands != null) {
                delegate.onAvailableCommandsChanged(extend(commands))
                return null
            }
        }

        return try {
            if (args == null) {
                method.invoke(delegate)
            } else {
                method.invoke(delegate, *args)
            }
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    companion object {
        fun createProxy(
            delegate: Player.Listener,
            extend: (Player.Commands) -> Player.Commands,
        ): Player.Listener =
            Proxy.newProxyInstance(
                Player.Listener::class.java.classLoader,
                arrayOf(Player.Listener::class.java),
                ExtendedCommandsListener(delegate, extend),
            ) as Player.Listener
    }
}
