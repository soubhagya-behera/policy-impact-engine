package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class SsrfGuardTest {

	@Test
	void privateIpLiteralIsRejected() {
		SsrfGuard guard = new SsrfGuard();

		assertThatThrownBy(() -> guard.validateUrl("https://127.0.0.1/privacy"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("blocked");
	}

	@Test
	void tenNetworkIsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https://10.0.0.5/policy"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void private172IsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https://172.16.5.4/policy"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void private192IsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https://192.168.1.20/policy"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void ipv6LoopbackIsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https://[::1]/policy"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void ipv6LinkLocalIsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https://[fe80::1]/policy"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void publicAddressIsAccepted() {
		SsrfGuard guard = new SsrfGuard();
		// 8.8.8.8 is public; literal IP validation should pass (no exception)
		guard.validateUrl("https://8.8.8.8/policy");
	}

	@Test
	void hostnameResolvingToPrivateIsRejectedViaMockResolver() {
		DnsResolver mockPrivate = host -> new InetAddress[]{
				InetAddress.getByName("10.0.0.1")
		};
		SsrfGuard guard = new SsrfGuard(mockPrivate);

		assertThatThrownBy(() -> guard.validateHost("private.example.com"))
				.isInstanceOf(PolicyFetchException.class)
				.hasMessageContaining("blocked");
	}

	@Test
	void hostnameResolvingToPublicIsAcceptedViaMockResolver() {
		DnsResolver mockPublic = host -> new InetAddress[]{
				InetAddress.getByName("8.8.8.8")
		};
		SsrfGuard guard = new SsrfGuard(mockPublic);
		// should not throw
		guard.validateHost("public.example.com");
		guard.validateUrl("https://public.example.com/policy");
	}

	@Test
	void hostnameResolvingToMixedAddressesIsRejectedIfAnyPrivate() {
		DnsResolver mockMixed = host -> new InetAddress[]{
				InetAddress.getByName("8.8.8.8"),
				InetAddress.getByName("192.168.1.1")
		};
		SsrfGuard guard = new SsrfGuard(mockMixed);

		assertThatThrownBy(() -> guard.validateHost("mixed.example.com"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void urlWithoutHostIsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https:///nohost"))
				.isInstanceOf(PolicyFetchException.class);
	}

	@Test
	void ipv6UlaIsRejected() {
		SsrfGuard guard = new SsrfGuard();
		assertThatThrownBy(() -> guard.validateUrl("https://[fc00::1]/policy"))
				.isInstanceOf(PolicyFetchException.class);
	}
}
