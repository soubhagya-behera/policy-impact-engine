package com.soubhagya.policyimpactengine.policy.fetch;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Resolves a hostname to its IP addresses. Abstracted for deterministic
 * testing without real DNS lookups.
 */
@FunctionalInterface
public interface DnsResolver {

	InetAddress[] resolve(String host) throws UnknownHostException;
}
