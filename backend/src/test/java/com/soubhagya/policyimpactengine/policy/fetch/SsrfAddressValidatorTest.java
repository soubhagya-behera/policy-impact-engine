package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfAddressValidatorTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"127.0.0.1",
			"127.0.0.2",
			"127.255.255.255"
	})
	void loopbackIsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
		assertThatThrownBy(() -> SsrfAddressValidator.validate(addr))
				.isInstanceOf(PolicyFetchException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"10.0.0.1",
			"10.255.255.255",
			"172.16.0.1",
			"172.31.255.255",
			"192.168.1.1",
			"192.168.255.255"
	})
	void privateAddressesAreBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@Test
	void public172OutsidePrivateRangeIsAllowed() throws Exception {
		// 172.32.0.1 is outside 172.16/12
		InetAddress addr = InetAddress.getByName("172.32.0.1");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"192.168.0.0",
			"10.1.2.3",
			"172.20.5.4"
	})
	void privateAddressesBlockedViaIsSiteLocal(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(addr.isSiteLocalAddress()).isTrue();
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"169.254.1.1",
			"169.254.255.255"
	})
	void linkLocalIPv4IsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"0.0.0.0"
	})
	void unspecifiedIPv4IsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"224.0.0.1",
			"239.255.255.255"
	})
	void multicastIPv4IsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"100.64.0.1",
			"100.127.255.255"
	})
	void cgnatIsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@Test
	void publicAddressIsAllowed() throws Exception {
		InetAddress addr = InetAddress.getByName("8.8.8.8");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isFalse();
		SsrfAddressValidator.validate(addr); // should not throw
	}

	@Test
	void publicAddressOneOneIsAllowed() throws Exception {
		InetAddress addr = InetAddress.getByName("1.1.1.1");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isFalse();
	}

	@Test
	void publicAddress93IsAllowed() throws Exception {
		InetAddress addr = InetAddress.getByName("93.184.216.34");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isFalse();
	}

	@Test
	void ipv6LoopbackIsBlocked() throws Exception {
		InetAddress addr = InetAddress.getByName("::1");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"fe80::1",
			"fe80::1234"
	})
	void ipv6LinkLocalIsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"fc00::1",
			"fd00::1",
			"fc12:3456:789a::1"
	})
	void ipv6UniqueLocalIsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@Test
	void ipv6UnspecifiedIsBlocked() throws Exception {
		InetAddress addr = InetAddress.getByName("::");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"ff02::1",
			"ff00::1"
	})
	void ipv6MulticastIsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@Test
	void ipv6DocumentationIsBlocked() throws Exception {
		InetAddress addr = InetAddress.getByName("2001:db8::1");
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}

	@Test
	void ipv6PublicIsAllowed() throws Exception {
		// 2606:4700:4700::1111 is Cloudflare public
		InetAddress addr = InetAddress.getByName("2606:4700:4700::1111");
		// May not resolve on all hosts; check fallback
		assertThat(SsrfAddressValidator.isBlocked(addr)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"192.0.2.1",
			"198.51.100.1",
			"203.0.113.1"
	})
	void testNetIsBlocked(String ip) throws Exception {
		InetAddress addr = InetAddress.getByName(ip);
		assertThat(SsrfAddressValidator.isBlocked(addr)).isTrue();
	}
}
