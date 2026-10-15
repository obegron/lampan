package com.egron.lampan.raop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlaySyncPacketTest {
    @Test
    fun initialAndPeriodicSyncRequestTwoSecondsEvenAcrossRtpWrap() {
        for (first in listOf(true, false)) {
            for (rtp in listOf(0L, 17_600L, 88_200L, 0xFFFF_FFF0L, 0x1_0000_0010L)) {
                val packet = AirPlaySyncPacket.encode(first, rtp, 0x1234_5678_9ABC_DEF0L)
                assertEquals(20, packet.size)
                val bytes = ByteBuffer.wrap(packet)
                assertEquals(if (first) 0x90 else 0x80, bytes.get().toInt() and 0xFF)
                assertEquals(0xD4, bytes.get().toInt() and 0xFF)
                assertEquals(7, bytes.short.toInt())
                val playbackRtp = bytes.int.toLong() and 0xFFFF_FFFFL
                assertEquals(0x1234_5678_9ABC_DEF0L, bytes.long)
                val currentRtp = bytes.int.toLong() and 0xFFFF_FFFFL
                assertEquals(rtp and 0xFFFF_FFFFL, currentRtp)
                assertEquals(88_200L, (currentRtp - playbackRtp) and 0xFFFF_FFFFL)
            }
        }
    }

    @Test
    fun raopWirePacketsAndAirPlay2FormatGiveSamePlaybackTimeAfterPreroll() {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 2_000
            DatagramSocket(0, loopback).use { sender ->
                val session = RaopSession("127.0.0.1")
                session.setupForTest(
                    "test", "test", "127.0.0.1", receiver.localPort, null,
                    sender, sender, receiver.localPort,
                )
                // Exercise the production AP1 sender, including its RTP advance.
                session.sendFrame(ByteArray(352 * 4))
                receive(receiver) // initial sync
                receive(receiver) // silent preroll audio
                session.synchronizeAt(10_000L)
                val initial = receive(receiver)
                val anchor = ByteBuffer.wrap(initial).getLong(8)
                val ap2 = AirPlaySyncPacket.encode(true, 88_200L, anchor)
                assertEquals(playbackTime(ap2, 88_200L), playbackTime(initial, 352L), 0.000001)

                // AP1 emits its next periodic sync before packet 125.
                repeat(124) {
                    session.sendFrame(ByteArray(352 * 4))
                    receive(receiver)
                }
                session.sendFrame(ByteArray(352 * 4))
                val periodic = receive(receiver)
                receive(receiver)
                assertEquals(0x80, periodic[0].toInt() and 0xFF)
                val periodicBytes = ByteBuffer.wrap(periodic)
                val periodicAp2 = AirPlaySyncPacket.encode(
                    false, 88_200L + 124L * 352, periodicBytes.getLong(8),
                )
                assertEquals(
                    playbackTime(periodicAp2, 88_200L + 124L * 352),
                    playbackTime(periodic, 125L * 352),
                    0.000001,
                )
            }
        }
    }

    // Interpret the wire as a receiver: the earlier RTP field is due at NTP;
    // the current audio sample plays later by their unsigned frame difference.
    private fun playbackTime(packet: ByteArray, audioRtp: Long): Double {
        val bytes = ByteBuffer.wrap(packet)
        val dueRtp = bytes.getInt(4).toLong() and 0xFFFF_FFFFL
        val ntp = bytes.getLong(8)
        val seconds = (ntp ushr 32).toDouble() +
            (ntp and 0xFFFF_FFFFL).toDouble() / 4_294_967_296.0
        return seconds + ((audioRtp - dueRtp) and 0xFFFF_FFFFL) / 44_100.0
    }

    private fun receive(socket: DatagramSocket): ByteArray {
        val packet = DatagramPacket(ByteArray(2_048), 2_048)
        socket.receive(packet)
        return packet.data.copyOf(packet.length)
    }
}
