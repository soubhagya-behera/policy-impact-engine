package com.soubhagya.policyimpactengine.policy.fetch;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

/**
 * Network-level SSRF protection for policy fetching.
 *
 * <p>Resolves the hostname before connecting and rejects the request if any
 * resolved address is non-public (loopback, private, link-local, unspecified,
 * multicast, IPv6 ULA, etc.). Uses {@link SsrfAddressValidator} for the
 * per-address decision.
 *
 * <p><b>TOCTOU / DNS rebinding limitation:</b> this guard performs a
 * pre-resolution check, but the underlying {@code HttpClient} performs its
 * own DNS resolution at connection time. There is a time-of-check /
 * time-of-use gap: a hostname whose DNS record changes between validation
 * and connection could still reach a private address. Java's standard
 * {@code HttpClient} does not expose a way to pin the validated IP for the
 * actual socket connection. This guard is therefore best-effort;
 * complete rebinding protection would require a custom connection layer
 * or a validating DNS resolver integrated with the HTTP client.
 *
 * <p>Redirects remain disabled in this slice; per-redirect revalidation
 * will be added when redirects are enabled.
 */
@Component
public class SsrfGuard {

	private final DnsResolver dnsResolver;

	public SsrfGuard() {
		this(InetAddress::getAllByName);
	}

	public SsrfGuard(DnsResolver dnsResolver) {
		if (dnsResolver == null) {
			throw new IllegalArgumentException("DnsResolver must not be null");
		}
		this.dnsResolver = dnsResolver;
	}

	/**
	 * Validates the host portion of the given URL.
	 *
	 * @throws PolicyFetchException if the URL is blank, has no host, cannot be resolved,
	 *                              or resolves to a blocked address
	 */
	public void validateUrl(String url) {
		if (url == null || url.isBlank()) {
			throw new PolicyFetchException("URL must not be blank");
		}
		URI uri;
		try {
			uri = URI.create(url.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new PolicyFetchException("Invalid URL: " + url, ex);
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new PolicyFetchException("URL must contain a valid host: " + url);
		}
		validateHost(host);
	}

	/**
	 * Resolves the hostname and validates every returned address.
	 * If ANY address is blocked, the host is rejected.
	 */
	public void validateHost(String host) {
		if (host == null || host.isBlank()) {
			throw new PolicyFetchException("Host must not be blank");
		}
		InetAddress[] addresses;
		try {
			addresses = dnsResolver.resolve(host);
		}
		catch (UnknownHostException ex) {
			// Phase 2U.1 classification: resolution itself failing is
			// environmental (transient); a resolved-but-blocked address
			// below stays permanent via the default constructor.
			throw new PolicyFetchException("Failed to resolve host: " + host, null, ex, true);
		}
		if (addresses == null || addresses.length == 0) {
			throw new PolicyFetchException("No addresses resolved for host: " + host, null, null, true);
		}
		for (InetAddress address : addresses) {
			SsrfAddressValidator.validate(address);
		}
	}

	/**
	 * Validates a single resolved address.
	 */
	public void validateAddress(InetAddress address) {
		SsrfAddressValidator.validate(address);
	}
}
