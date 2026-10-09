package com.omni.assistant.ai.gemini

import com.omni.assistant.core.logging.OmniLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException

object DirectDnsResolver {

    private val DNS_SERVERS = listOf(
        byteArrayOf(8.toByte(), 8.toByte(), 8.toByte(), 8.toByte()),       // 8.8.8.8 (Google Primary)
        byteArrayOf(1.toByte(), 1.toByte(), 1.toByte(), 1.toByte()),       // 1.1.1.1 (Cloudflare Primary)
        byteArrayOf(8.toByte(), 8.toByte(), 4.toByte(), 4.toByte()),       // 8.8.4.4 (Google Secondary)
        byteArrayOf(1.toByte(), 0.toByte(), 0.toByte(), 1.toByte())        // 1.0.0.1 (Cloudflare Secondary)
    )

    private val DOH_ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query"
    )

    private const val DNS_PORT = 53
    private const val TIMEOUT_MS = 3500

    fun resolve(hostname: String): List<InetAddress> {
        val trimmedHost = hostname.trim()
        if (trimmedHost.isBlank()) return emptyList()

        // 1. Try UDP DNS first
        val queryPacket = buildDnsQuery(trimmedHost)
        for (ipBytes in DNS_SERVERS) {
            val serverIpStr = ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            try {
                val serverAddr = InetAddress.getByAddress(ipBytes)
                DatagramSocket().use { socket ->
                    socket.soTimeout = TIMEOUT_MS
                    val outPacket = DatagramPacket(queryPacket, queryPacket.size, serverAddr, DNS_PORT)
                    socket.send(outPacket)

                    val buffer = ByteArray(1024)
                    val inPacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(inPacket)

                    val ips = parseDnsResponse(buffer, inPacket.length, trimmedHost)
                    if (ips.isNotEmpty()) {
                        OmniLogger.i("DirectDnsResolver", "Resolved $trimmedHost via UDP DNS ($serverIpStr)")
                        return ips
                    }
                }
            } catch (e: Exception) {
                OmniLogger.w("DirectDnsResolver", "UDP DNS query to $serverIpStr failed for $trimmedHost: ${e.message}")
            }
        }

        // 2. Fallback: Query DNS-over-HTTPS (DoH) via HTTPS port 443
        for (endpoint in DOH_ENDPOINTS) {
            try {
                val url = java.net.URL("$endpoint?name=$trimmedHost&type=A")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/dns-json")
                conn.setRequestProperty("User-Agent", "OmniAssistant/1.0")

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val ips = parseDohJsonResponse(body, trimmedHost)
                    if (ips.isNotEmpty()) {
                        OmniLogger.i("DirectDnsResolver", "Resolved $trimmedHost via DoH ($endpoint) to ${ips.size} addresses")
                        return ips
                    }
                } else {
                    OmniLogger.w("DirectDnsResolver", "DoH returned HTTP ${conn.responseCode} for $trimmedHost")
                }
            } catch (e: Exception) {
                OmniLogger.w("DirectDnsResolver", "DoH query to $endpoint failed for $trimmedHost: ${e.message}")
            }
        }

        return emptyList()
    }

    fun parseDohJsonResponse(jsonString: String, hostname: String): List<InetAddress> {
        return try {
            val root = org.json.JSONObject(jsonString)
            val answers = root.optJSONArray("Answer") ?: return emptyList()
            val list = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val ans = answers.getJSONObject(i)
                if (ans.optInt("type") == 1) { // Type 1 = A record (IPv4)
                    val ipStr = ans.optString("data").trim()
                    val parts = ipStr.split('.')
                    if (parts.size == 4) {
                        try {
                            val bytes = ByteArray(4) { idx -> parts[idx].toInt().toByte() }
                            list.add(InetAddress.getByAddress(hostname, bytes))
                        } catch (e: Exception) {
                            // Skip malformed IP
                        }
                    }
                }
            }
            list
        } catch (e: Exception) {
            OmniLogger.w("DirectDnsResolver", "Failed to parse DoH JSON response: ${e.message}")
            emptyList()
        }
    }

    fun buildDnsQuery(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        val dataOut = DataOutputStream(out)

        // Transaction ID
        dataOut.writeShort(0x4A1F)
        // Flags: standard query with recursion desired (0x0100)
        dataOut.writeShort(0x0100)
        // QDCOUNT = 1 (1 question)
        dataOut.writeShort(0x0001)
        // ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
        dataOut.writeShort(0x0000)
        dataOut.writeShort(0x0000)
        dataOut.writeShort(0x0000)

        // QNAME: length-prefixed domain labels
        for (label in hostname.split('.')) {
            val labelBytes = label.toByteArray(Charsets.US_ASCII)
            dataOut.writeByte(labelBytes.size)
            dataOut.write(labelBytes)
        }
        dataOut.writeByte(0) // Root label null terminator

        // QTYPE = 1 (A record IPv4)
        dataOut.writeShort(0x0001)
        // QCLASS = 1 (IN Internet)
        dataOut.writeShort(0x0001)

        dataOut.flush()
        return out.toByteArray()
    }

    fun parseDnsResponse(data: ByteArray, length: Int, hostname: String): List<InetAddress> {
        if (length < 12) return emptyList()
        val inStream = DataInputStream(ByteArrayInputStream(data, 0, length))

        val id = inStream.readShort()
        val flags = inStream.readShort().toInt() and 0xFFFF
        val rcode = flags and 0x000F
        if (rcode != 0) {
            OmniLogger.w("DirectDnsResolver", "DNS response returned error RCODE: $rcode")
            return emptyList()
        }

        val qdCount = inStream.readShort().toInt() and 0xFFFF
        val anCount = inStream.readShort().toInt() and 0xFFFF
        inStream.readShort() // nsCount
        inStream.readShort() // arCount

        // Skip Question records
        for (q in 0 until qdCount) {
            skipName(inStream)
            inStream.readShort() // QTYPE
            inStream.readShort() // QCLASS
        }

        val result = mutableListOf<InetAddress>()

        // Parse Answer records
        for (a in 0 until anCount) {
            skipName(inStream)
            val type = inStream.readShort().toInt() and 0xFFFF
            val clazz = inStream.readShort().toInt() and 0xFFFF
            val ttl = inStream.readInt()
            val rdLength = inStream.readShort().toInt() and 0xFFFF

            if (type == 1 && rdLength == 4) { // A record (IPv4 address)
                val ipBytes = ByteArray(4)
                inStream.readFully(ipBytes)
                try {
                    result.add(InetAddress.getByAddress(hostname, ipBytes))
                } catch (e: Exception) {
                    OmniLogger.w("DirectDnsResolver", "Failed to build InetAddress: ${e.message}")
                }
            } else {
                inStream.skipBytes(rdLength)
            }
        }

        return result
    }

    private fun skipName(inStream: DataInputStream) {
        while (true) {
            val len = inStream.readByte().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) { // DNS pointer compression
                inStream.readByte()
                break
            } else {
                inStream.skipBytes(len)
            }
        }
    }
}
