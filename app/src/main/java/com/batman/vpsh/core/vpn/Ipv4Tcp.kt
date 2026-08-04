package com.batman.vpsh.core.vpn

import java.io.ByteArrayOutputStream

object Ipv4Tcp {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    data class Ipv4Header(
        val version: Int,
        val ihl: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val headerLength: Int
    )

    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val window: Int,
        val headerLength: Int
    ) {
        fun has(flag: Int) = (flags and flag) != 0
    }

    data class UdpHeader(val srcPort: Int, val dstPort: Int, val length: Int, val headerLength: Int)

    fun parseIpv4(buf: ByteArray, len: Int): Ipv4Header? {
        if (len < 20) return null
        val versionIhl = buf[0].toInt() and 0xFF
        val version = versionIhl shr 4
        if (version != 4) return null
        val ihl = versionIhl and 0x0F
        val headerLength = ihl * 4
        if (headerLength < 20 || len < headerLength) return null
        val totalLength = u16(buf, 2)
        val flagsFrag = u16(buf, 6)
        val moreFragments = (flagsFrag and 0x2000) != 0
        val fragOffset = flagsFrag and 0x1FFF
        if (moreFragments || fragOffset != 0) return null 
        val protocol = buf[9].toInt() and 0xFF
        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)
        return Ipv4Header(version, ihl, totalLength, protocol, srcIp, dstIp, headerLength)
    }

    fun parseTcp(buf: ByteArray, offset: Int, len: Int): TcpHeader? {
        if (len - offset < 20) return null
        val srcPort = u16(buf, offset)
        val dstPort = u16(buf, offset + 2)
        val seq = u32(buf, offset + 4)
        val ack = u32(buf, offset + 8)
        val dataOffsetByte = buf[offset + 12].toInt() and 0xFF
        val headerLength = (dataOffsetByte shr 4) * 4
        val flags = buf[offset + 13].toInt() and 0x3F
        val window = u16(buf, offset + 14)
        if (headerLength < 20 || offset + headerLength > len) return null
        return TcpHeader(srcPort, dstPort, seq, ack, flags, window, headerLength)
    }

    fun parseUdp(buf: ByteArray, offset: Int, len: Int): UdpHeader? {
        if (len - offset < 8) return null
        val srcPort = u16(buf, offset)
        val dstPort = u16(buf, offset + 2)
        val length = u16(buf, offset + 4)
        return UdpHeader(srcPort, dstPort, length, 8)
    }

    fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpHeaderLen = 20
        val totalLen = 20 + tcpHeaderLen + payload.size
        val out = ByteArray(totalLen)

        out[0] = (0x40 or 5).toByte() 
        out[1] = 0
        putU16(out, 2, totalLen)
        putU16(out, 4, 0) 
        putU16(out, 6, 0) 
        out[8] = 64 
        out[9] = PROTO_TCP.toByte()
        putU16(out, 10, 0) 
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        val ipChecksum = checksum(out, 0, 20)
        putU16(out, 10, ipChecksum)

        val tcpOffset = 20
        putU16(out, tcpOffset, srcPort)
        putU16(out, tcpOffset + 2, dstPort)
        putU32(out, tcpOffset + 4, seq)
        putU32(out, tcpOffset + 8, ack)
        out[tcpOffset + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        out[tcpOffset + 13] = (flags and 0xFF).toByte()
        putU16(out, tcpOffset + 14, window)
        putU16(out, tcpOffset + 16, 0) 
        putU16(out, tcpOffset + 18, 0) 
        System.arraycopy(payload, 0, out, tcpOffset + tcpHeaderLen, payload.size)

        val tcpChecksum = tcpUdpChecksum(srcIp, dstIp, PROTO_TCP, out, tcpOffset, tcpHeaderLen + payload.size)
        putU16(out, tcpOffset + 16, tcpChecksum)
        return out
    }

    fun buildUdpPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)

        out[0] = (0x40 or 5).toByte()
        out[1] = 0
        putU16(out, 2, totalLen)
        putU16(out, 4, 0)
        putU16(out, 6, 0)
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        putU16(out, 10, 0)
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        putU16(out, 10, checksum(out, 0, 20))

        val udpOffset = 20
        putU16(out, udpOffset, srcPort)
        putU16(out, udpOffset + 2, dstPort)
        putU16(out, udpOffset + 4, udpLen)
        putU16(out, udpOffset + 6, 0) 
        System.arraycopy(payload, 0, out, udpOffset + 8, payload.size)
        return out
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    private fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun checksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + len
        while (i < end - 1) {
            sum += u16(buf, i)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private fun tcpUdpChecksum(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, buf: ByteArray, offset: Int, len: Int): Int {
        val pseudoAndPayload = ByteArrayOutputStream(12 + len)
        pseudoAndPayload.write(srcIp)
        pseudoAndPayload.write(dstIp)
        pseudoAndPayload.write(0)
        pseudoAndPayload.write(protocol)
        pseudoAndPayload.write((len shr 8) and 0xFF)
        pseudoAndPayload.write(len and 0xFF)
        pseudoAndPayload.write(buf, offset, len)
        val bytes = pseudoAndPayload.toByteArray()
        return checksum(bytes, 0, bytes.size)
    }

    fun ipToString(ip: ByteArray) = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
}
