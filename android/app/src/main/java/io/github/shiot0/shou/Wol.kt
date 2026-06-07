package io.github.shiot0.shou

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN. Turning a sleeping Shou PC on from the couch is the whole point of
 * the phone being a "remote" — so each saved server can store the PC's MAC and we
 * broadcast a standard magic packet to wake it before connecting.
 *
 * A magic packet is 6×0xFF followed by the 6-byte MAC repeated 16 times, sent as a
 * UDP broadcast (so it reaches a host that has no IP yet because it's asleep).
 */
object Wol {

    private val PORTS = intArrayOf(9, 7)  // common WOL ports; send to both

    /** Returns true if a packet was sent. Accepts MACs as AA:BB:.. / AA-BB-.. / aabb.. */
    fun wake(mac: String): Boolean {
        val bytes = parseMac(mac) ?: return false
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (rep in 0 until 16) System.arraycopy(bytes, 0, packet, 6 + rep * 6, 6)

        var sent = false
        // 255.255.255.255 reaches the local subnet; also try a couple of broadcast-ish
        // targets so it works across the handful of router setups people actually have.
        val targets = listOf("255.255.255.255")
        try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                for (host in targets) {
                    val addr = InetAddress.getByName(host)
                    for (port in PORTS) {
                        try {
                            sock.send(DatagramPacket(packet, packet.size, addr, port))
                            sent = true
                        } catch (e: Exception) {
                            // keep trying the other port/target
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return sent
        }
        return sent
    }

    /** 12 hex digits (with optional :/-/. separators) -> 6 bytes, or null if malformed. */
    private fun parseMac(mac: String): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return null
        return try {
            ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
