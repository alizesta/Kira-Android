package ai.kira.mobile.data.remote

import java.net.InetAddress

/** Resolves and validates addresses once so the caller can pin the checked result. */
internal object ConnectorAddressPolicy {
    fun resolvePublic(
        host: String,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
    ): List<InetAddress> {
        val addresses = resolver(host).distinct()
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
            "مقصد Connector به IP خصوصی یا رزروشده resolve شد."
        }
        return addresses
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress) return false

        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            return when {
                a == 0 || a == 10 || a == 127 || a >= 224 -> false
                a == 100 && b in 64..127 -> false
                a == 169 && b == 254 -> false
                a == 172 && b in 16..31 -> false
                a == 192 && b == 168 -> false
                a == 192 && b == 0 -> false
                a == 192 && b == 88 && c == 99 -> false
                a == 198 && b in 18..19 -> false
                a == 198 && b == 51 && c == 100 -> false
                a == 203 && b == 0 && c == 113 -> false
                else -> true
            }
        }

        if (bytes.size == 16) {
            // IPv4-mapped IPv6 addresses must pass the IPv4 policy too.
            if ((0 until 10).all { bytes[it].toInt() == 0 } &&
                bytes[10].toInt() == 0xff.toByte().toInt() &&
                bytes[11].toInt() == 0xff.toByte().toInt()
            ) {
                return isPublicAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            }
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            val d = bytes[3].toInt() and 0xff
            if ((a and 0xe0) != 0x20) return false // Global unicast 2000::/3 only.
            if (a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8) return false
            if (a == 0x20 && b == 0x01 && c == 0x00 && d == 0x02) return false
            if (a == 0x20 && b == 0x01 && c == 0x00 && d == 0x00) return false
            if (a == 0x20 && b == 0x01 && c == 0x00 && (d in 0x10..0x1f || d in 0x20..0x2f)) return false // ORCHID special-use space.
            if (a == 0x20 && b == 0x02) return false
            return true
        }
        return false
    }
}