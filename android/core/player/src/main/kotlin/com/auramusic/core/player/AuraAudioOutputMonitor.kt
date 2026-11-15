package com.auramusic.core.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.AudioOutputType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitor reactivo de la ruta de salida de audio del dispositivo mediante [AudioDeviceCallback].
 *
 * Disponible desde Android 8.0 (`minSdk = 26`, API 23+) sin solicitar permisos adicionales
 * de Bluetooth (`BLUETOOTH_CONNECT`) ni escanear adaptadores cercanos, inspeccionando únicamente
 * las salidas activas reportadas por [AudioManager.GET_DEVICES_OUTPUTS].
 */
class AuraAudioOutputMonitor(
    context: Context,
) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _outputRoute = MutableStateFlow(resolveCurrentRoute())
    val outputRoute: StateFlow<AudioOutputRouteState> = _outputRoute.asStateFlow()

    private var registered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshRoute()
        }
    }

    /**
     * Registra el listener de cambios de hardware de salida si aún no está activo.
     */
    @Synchronized
    fun start() {
        if (registered) {
            refreshRoute()
            return
        }
        registered = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refreshRoute()
    }

    /**
     * Desregistra el callback al destruir el componente propietario.
     */
    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        runCatching {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        }
    }

    fun refreshRoute(): AudioOutputRouteState {
        val next = resolveCurrentRoute()
        _outputRoute.value = next
        return next
    }

    private fun resolveCurrentRoute(): AudioOutputRouteState {
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()

        // Prioridad de selección: Bluetooth -> USB-C -> Cable analógico -> Salida externa -> Altavoz.
        val bluetooth = outputs.firstOrNull { isBluetoothHeadphoneDevice(it) }
        if (bluetooth != null) {
            return buildRouteState(AudioOutputType.BLUETOOTH_HEADSET, bluetooth)
        }

        val usb = outputs.firstOrNull { isUsbHeadphoneDevice(it) }
        if (usb != null) {
            return buildRouteState(AudioOutputType.USB_HEADSET, usb)
        }

        val wired = outputs.firstOrNull { isWiredHeadphoneDevice(it) }
        if (wired != null) {
            return buildRouteState(AudioOutputType.WIRED_HEADSET, wired)
        }

        val externalOther = outputs.firstOrNull { isExternalOtherOutput(it) }
        if (externalOther != null) {
            return buildRouteState(AudioOutputType.OTHER, externalOther)
        }

        return AudioOutputRouteState(
            outputType = AudioOutputType.SPEAKER,
            deviceName = AudioOutputType.SPEAKER.displayLabel,
            isHeadphonesConnected = false,
        )
    }

    private fun buildRouteState(
        outputType: AudioOutputType,
        device: AudioDeviceInfo,
    ): AudioOutputRouteState {
        val rawName = device.productName?.toString()?.trim().orEmpty()
        val deviceName = if (
            rawName.isNotEmpty() &&
            !rawName.equals(Build.MODEL, ignoreCase = true) &&
            !rawName.equals("Android", ignoreCase = true)
        ) {
            rawName
        } else {
            outputType.displayLabel
        }
        return AudioOutputRouteState(
            outputType = outputType,
            deviceName = deviceName,
            isHeadphonesConnected = outputType.isHeadphones,
        )
    }

    private fun isBluetoothHeadphoneDevice(device: AudioDeviceInfo): Boolean {
        val type = device.type
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            return true
        }
        return false
    }

    private fun isUsbHeadphoneDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
            else -> false
        }
    }

    private fun isWiredHeadphoneDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG -> true
            else -> false
        }
    }

    private fun isExternalOtherOutput(device: AudioDeviceInfo): Boolean {
        val type = device.type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
            return true
        }
        return when (type) {
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
            else -> false
        }
    }
}
