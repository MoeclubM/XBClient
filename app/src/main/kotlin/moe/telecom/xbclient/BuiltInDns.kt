package moe.telecom.xbclient

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import kotlin.random.Random

class BuiltInDns(private val server: String) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.matches(Regex("^[0-9.]+$")) || hostname.matches(Regex("^[0-9A-Fa-f:.]+$")) && hostname.contains(":")) {
            return listOf(InetAddress.getByName(hostname))
        }
        val addresses = dnsQuery(hostname, DNS_TYPE_A) + dnsQuery(hostname, DNS_TYPE_AAAA)
        if (addresses.isEmpty()) {
            throw UnknownHostException("$hostname has no A/AAAA record from $server")
        }
        return addresses
    }

    private fun dnsQuery(hostname: String, type: Int): List<InetAddress> {
        val id = Random.nextInt(0, 65536)
        val request = dnsRequest(id, hostname, type)
        val response = ByteArray(1500)
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MS
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(server, DNS_PORT)))
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            return dnsAnswers(response.copyOf(packet.length), id, type)
        }
    }

    private fun dnsRequest(id: Int, hostname: String, type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write((id ushr 8) and 0xff)
        output.write(id and 0xff)
        output.write(0x01)
        output.write(0x00)
        output.write(0x00)
        output.write(0x01)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        output.write(0x00)
        for (label in IDN.toASCII(hostname.trimEnd('.')).split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0x00)
        output.write((type ushr 8) and 0xff)
        output.write(type and 0xff)
        output.write(0x00)
        output.write(0x01)
        return output.toByteArray()
    }

    private fun dnsAnswers(response: ByteArray, id: Int, type: Int): List<InetAddress> {
        if (response.size < DNS_HEADER_SIZE || readUInt16(response, 0) != id) {
            throw UnknownHostException("invalid DNS response from $server")
        }
        val rcode = response[3].toInt() and 0x0f
        if (rcode != 0) {
            throw UnknownHostException("DNS $server returned rcode $rcode")
        }
        var offset = DNS_HEADER_SIZE
        repeat(readUInt16(response, 4)) {
            offset = skipDnsName(response, offset) + 4
        }
        val addresses = mutableListOf<InetAddress>()
        repeat(readUInt16(response, 6)) {
            offset = skipDnsName(response, offset)
            val answerType = readUInt16(response, offset)
            val answerClass = readUInt16(response, offset + 2)
            val length = readUInt16(response, offset + 8)
            val dataOffset = offset + 10
            if (answerType == type && answerClass == 1 && dataOffset + length <= response.size) {
                if (type == DNS_TYPE_A && length == 4 || type == DNS_TYPE_AAAA && length == 16) {
                    addresses += InetAddress.getByAddress(response.copyOfRange(dataOffset, dataOffset + length))
                }
            }
            offset = dataOffset + length
        }
        return addresses
    }

    private fun skipDnsName(response: ByteArray, start: Int): Int {
        var offset = start
        while (offset < response.size) {
            val length = response[offset].toInt() and 0xff
            offset++
            if (length == 0) {
                return offset
            }
            if ((length and 0xc0) == 0xc0) {
                return offset + 1
            }
            offset += length
        }
        throw UnknownHostException("truncated DNS response from $server")
    }

    private fun readUInt16(response: ByteArray, offset: Int): Int =
        ((response[offset].toInt() and 0xff) shl 8) or (response[offset + 1].toInt() and 0xff)

    companion object {
        private const val DNS_PORT = 53
        private const val DNS_TIMEOUT_MS = 5000
        private const val DNS_HEADER_SIZE = 12
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_AAAA = 28
    }
}
