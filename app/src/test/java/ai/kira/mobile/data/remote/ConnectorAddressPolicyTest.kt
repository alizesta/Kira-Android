package ai.kira.mobile.data.remote

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorAddressPolicyTest {

    @Test
    fun rejectsPrivateAndSpecialUseIpv4Ranges() {
        listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.0.8",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1"
        ).forEach { address ->
            assertFalse("$address must not be accepted", ConnectorAddressPolicy.isPublicAddress(InetAddress.getByName(address)))
        }
    }

    @Test
    fun acceptsGloballyRoutableIpv4AndIpv6() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888")
            .forEach { address ->
                assertTrue("$address should be accepted", ConnectorAddressPolicy.isPublicAddress(InetAddress.getByName(address)))
            }
    }

    @Test
    fun rejectsLocalAndReservedIpv6Ranges() {
        listOf(
            "::",
            "::1",
            "fc00::1",
            "fd12:3456:789a::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "2001:2::1",
            "2001:10::1",
            "2001:20::1",
            "2001::1",
            "2002::1"
        ).forEach { address ->
            assertFalse("$address must not be accepted", ConnectorAddressPolicy.isPublicAddress(InetAddress.getByName(address)))
        }
    }

    @Test
    fun resolverMustReturnOnlyPublicAddresses() {
        val safe = InetAddress.getByName("8.8.8.8")
        val privateAddress = InetAddress.getByName("127.0.0.1")
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorAddressPolicy.resolvePublic("api.example.test") {
                assertEquals("api.example.test", it)
                listOf(safe, privateAddress)
            }
        }
    }

    @Test
    fun pinnedResultPreservesTheValidatedAddresses() {
        val addresses = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1"))
        val result = ConnectorAddressPolicy.resolvePublic("api.example.test") { addresses }
        assertEquals(addresses, result)
    }
}
