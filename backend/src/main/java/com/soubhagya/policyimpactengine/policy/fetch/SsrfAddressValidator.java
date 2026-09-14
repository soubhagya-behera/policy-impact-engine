package com.soubhagya.policyimpactengine.policy.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Validates whether an IP address is safe for outbound policy fetching.
 *
 * <p>Blocked ranges include loopback, private, link-local, unspecified,
 * multicast, and other non-public ranges per the SSRF requirements.
 * Uses standard {@link InetAddress} APIs plus explicit range checks where
 * the JDK helpers are insufficient (e.g. IPv6 ULA fc00::/7, CGNAT).
 */
public final class SsrfAddressValidator {

	private SsrfAddressValidator() {
	}

	/**
	 * Returns true if the address must be blocked.
	 */
	public static boolean isBlocked(InetAddress address) {
		if (address == null) {
			return true;
		}
		if (address.isAnyLocalAddress()) {
			return true;
		}
		if (address.isLoopbackAddress()) {
			return true;
		}
		if (address.isLinkLocalAddress()) {
			return true;
		}
		if (address.isSiteLocalAddress()) {
			return true;
		}
		if (address.isMulticastAddress()) {
			return true;
		}

		if (address instanceof Inet4Address) {
			return isBlockedIPv4(address.getAddress());
		}
		if (address instanceof Inet6Address) {
			return isBlockedIPv6(address.getAddress());
		}
		return false;
	}

	public static void validate(InetAddress address) {
		if (isBlocked(address)) {
			throw new PolicyFetchException(
					"SSRF protection: blocked address " + address.getHostAddress());
		}
	}

	private static boolean isBlockedIPv4(byte[] bytes) {
		int b0 = bytes[0] & 0xFF;
		int b1 = bytes[1] & 0xFF;
		int b2 = bytes[2] & 0xFF;
		int b3 = bytes[3] & 0xFF;

		// 0.0.0.0/8 unspecified
		if (b0 == 0) {
			return true;
		}
		// 127.0.0.0/8 loopback (already via isLoopback, keep explicit)
		if (b0 == 127) {
			return true;
		}
		// 10.0.0.0/8 private
		if (b0 == 10) {
			return true;
		}
		// 172.16.0.0/12 private
		if (b0 == 172 && b1 >= 16 && b1 <= 31) {
			return true;
		}
		// 192.168.0.0/16 private
		if (b0 == 192 && b1 == 168) {
			return true;
		}
		// 169.254.0.0/16 link-local
		if (b0 == 169 && b1 == 254) {
			return true;
		}
		// 224.0.0.0/4 multicast
		if (b0 >= 224 && b0 <= 239) {
			return true;
		}
		// 255.255.255.255 broadcast
		if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) {
			return true;
		}
		// 100.64.0.0/10 CGNAT (100.64.0.0 - 100.127.255.255)
		if (b0 == 100 && b1 >= 64 && b1 <= 127) {
			return true;
		}
		// 192.0.2.0/24 TEST-NET-1
		if (b0 == 192 && b1 == 0 && b2 == 2) {
			return true;
		}
		// 198.51.100.0/24 TEST-NET-2
		if (b0 == 198 && b1 == 51 && b2 == 100) {
			return true;
		}
		// 203.0.113.0/24 TEST-NET-3
		if (b0 == 203 && b1 == 0 && b2 == 113) {
			return true;
		}
		// 192.88.99.0/24 6to4 relay
		if (b0 == 192 && b1 == 88 && b2 == 99) {
			return true;
		}
		// 198.18.0.0/15 benchmarking
		if (b0 == 198 && (b1 == 18 || b1 == 19)) {
			return true;
		}
		return false;
	}

	private static boolean isBlockedIPv6(byte[] bytes) {
		int b0 = bytes[0] & 0xFF;
		int b1 = bytes[1] & 0xFF;

		// fc00::/7 unique-local (fc00:: and fd00::)
		if (b0 == 0xfc || b0 == 0xfd) {
			return true;
		}
		// 2001:db8::/32 documentation
		if (b0 == 0x20 && b1 == 0x01 && (bytes[2] & 0xFF) == 0x0d && (bytes[3] & 0xFF) == 0xb8) {
			return true;
		}
		// :: (unspecified) handled by isAnyLocalAddress
		// ::1 handled by isLoopbackAddress
		// fe80::/10 handled by isLinkLocalAddress
		// ff00::/8 handled by isMulticastAddress
		return false;
	}
}
