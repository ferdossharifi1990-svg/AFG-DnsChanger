package com.example.dnschanger

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    @Volatile private var running = false

    companion object {
        const val ACTION_START = "com.example.dnschanger.START"
        const val ACTION_STOP = "com.example.dnschanger.STOP"
        const val EXTRA_PRIMARY_DNS = "primary_dns"
        const val EXTRA_SECONDARY_DNS = "secondary_dns"
        private const val TAG = "DnsVpnService"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val MTU = 1500
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            else -> {
                val primary = intent?.getStringExtra(EXTRA_PRIMARY_DNS) ?: "1.1.1.1"
                val secondary = intent?.getStringExtra(EXTRA_SECONDARY_DNS) ?: "1.0.0.1"
                startVpn(primary, secondary)
            }
        }
        return START_STICKY
    }

    private fun startVpn(primaryDns: String, secondaryDns: String) {
        if (running) stopVpn()

        val builder = Builder()
            .setSession("DNS Changer")
            .addAddress(VPN_ADDRESS, 32)
            .setMtu(MTU)
            .addDnsServer(primaryDns)
            .addDnsServer(secondaryDns)
            .addRoute(primaryDns, 32)
            .addRoute(secondaryDns, 32)

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            return
        }

        running = true
        workerThread = thread(start = true) {
            runForwardingLoop(primaryDns)
        }
    }

    private fun stopVpn() {
        running = false
        workerThread?.interrupt()
        workerThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interface", e)
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun runForwardingLoop(dnsServer: String) {
        val fd = vpnInterface ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(MTU)

        val udpSocket = DatagramSocket()
        protect(udpSocket)

        try {
            while (running) {
                val length = input.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOf(length)
                if (!isIPv4(packet)) continue

                val ihl = (packet[0].toInt() and 0x0F) * 4
                val protocol = packet[9].toInt() and 0xFF
                if (protocol != 17) continue

                val udpOffset = ihl
                val srcPort = readUShort(packet, udpOffset)
                val dstPort = readUShort(packet, udpOffset + 2)
                if (dstPort != 53) continue

                val udpLength = readUShort(packet, udpOffset + 4)
                val payloadOffset = udpOffset + 8
                val payloadLength = udpLength - 8
                if (payloadLength <= 0 || payloadOffset + payloadLength > packet.size) continue

                val dnsQuery = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                val srcAddress = readIPv4(packet, 12)

                thread(start = true) {
                    try {
                        val response = forwardDnsQuery(udpSocket, dnsServer, dnsQuery)
                        if (response != null) {
                            val reply = buildReplyPacket(
                                srcIp = readIPv4(packet, 16),
                                dstIp = srcAddress,
                                srcPort = 53,
                                dstPort = srcPort,
                                payload = response
                            )
                            synchronized(output) {
                                output.write(reply)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Forward error", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Forwarding loop stopped", e)
        } finally {
            udpSocket.close()
        }
    }

    private fun forwardDnsQuery(socket: DatagramSocket, dnsServer: String, query: ByteArray): ByteArray? {
        return try {
            val address = InetAddress.getByName(dnsServer)
            val outPacket = DatagramPacket(query, query.size, address, 53)
            socket.send(outPacket)

            val responseBuffer = ByteArray(MTU)
            val inPacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.soTimeout = 5000
            socket.receive(inPacket)
            inPacket.data.copyOf(inPacket.length)
        } catch (e: Exception) {
            Log.e(TAG, "DNS query failed", e)
            null
        }
    }

    private fun isIPv4(packet: ByteArray): Boolean {
        return packet.isNotEmpty() && (packet[0].toInt() and 0xF0) == 0x40
    }

    private fun readUShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readIPv4(data: ByteArray, offset: Int): ByteArray {
        return data.copyOfRange(offset, offset + 4)
    }

    private fun buildReplyPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val buffer = ByteBuffer.allocate(totalLength)

        buffer.put(0x45.toByte())
        buffer.put(0x00.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0)
        buffer.putShort(0x4000.toShort())
        buffer.put(64.toByte())
        buffer.put(17.toByte())
        buffer.putShort(0)
        buffer.put(srcIp)
        buffer.put(dstIp)

        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0)

        buffer.put(payload)

        val packet = buffer.array()
        val checksum = ipHeaderChecksum(packet)
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
        return packet
    }

    private fun ipHeaderChecksum(packet: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < 20) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
