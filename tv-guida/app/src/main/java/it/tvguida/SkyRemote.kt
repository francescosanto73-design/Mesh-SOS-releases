package it.tvguida

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.delay

/**
 * Il telefono si presenta come tastiera Bluetooth (profilo HID) e "digita" il numero del canale.
 * Prima va associato (pairing) al box dalle impostazioni Bluetooth del box Sky.
 * Non è garantito che il Sky Stream accetti una tastiera generica: va provato.
 */
@SuppressLint("MissingPermission")
class SkyRemote(private val ctx: Context, private val onState: (String) -> Unit) {

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false

    val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList() ?: emptyList()

    fun start() {
        val a = adapter ?: return onState("Bluetooth non disponibile")
        if (!a.isEnabled) return onState("Attiva il Bluetooth")
        a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "TV Guida", "Telecomando", "TvGuida",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR,
                )
                hid!!.registerApp(sdp, null, null, ctx.mainExecutor, callback)
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null; registered = false; host = null
                onState("Disconnesso")
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return onState("Servizio HID non pronto")
        if (!registered) return onState("App HID non registrata (riprova)")
        onState("Connessione a ${device.name}…")
        h.connect(device)
    }

    val connected get() = host != null

    /** Digita le cifre del canale (e opzionalmente OK). */
    suspend fun goToChannel(number: Int): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        for (c in number.toString()) {
            val usage = if (c == '0') 0x27 else 0x1E + (c - '1')
            h.sendReport(d, REPORT_ID, byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0))
            delay(60)
            h.sendReport(d, REPORT_ID, ByteArray(8))
            delay(350)
        }
        return true
    }

    fun stop() {
        hid?.let { h ->
            host?.let { h.disconnect(it) }
            h.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null; host = null; registered = false
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@SkyRemote.registered = registered
            onState(if (registered) "Pronto: scegli il box" else "Registrazione HID fallita")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { host = device; onState("Collegato a ${device.name}") }
                BluetoothProfile.STATE_DISCONNECTED -> { if (host == device) host = null; onState("Non collegato") }
            }
        }
    }

    companion object {
        const val REPORT_ID = 1
        // Tastiera HID standard con report ID 1 (modificatori, riservato, 6 tasti).
        val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), REPORT_ID.toByte(),
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02,
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte(),
        )
    }
}
