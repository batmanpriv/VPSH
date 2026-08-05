package com.batman.vpsh.core.vpn

fun interface PacketSink {
    fun write(packet: ByteArray)
}
