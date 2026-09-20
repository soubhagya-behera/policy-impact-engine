package com.soubhagya.policyimpactengine.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Phase 8B — deterministic unit tests for {@link JwtService}.
 *
 * <p>No Spring context. Fixed clocks pin issuance and validation;
 * every failure mode must collapse to {@link JwtInvalidException}
 * with no reason detail.
 */
class JwtServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");
	private static final String SECRET = "test-secret-for-jwt-service-unit-tests-0000";
	private static final String OTHER_SECRET = "other-test-secret-for-wrong-key-cases-00000";

	private static JwtService serviceAt(Instant instant) {
		return new JwtService(new JwtProperties(SECRET, null), Clock.fixed(instant, ZoneOffset.UTC));
	}

	@Test
	void issueParseRoundTripReturnsSubjectUuid() {
		JwtService service = serviceAt(NOW);
		UUID userId = UUID.randomUUID();

		String token = service.issueAccessToken(userId);

		assertThat(service.parseUserId(token)).isEqualTo(userId);
		assertThat(service.accessTokenExpiresInSeconds()).isEqualTo(900L);
	}

	@Test
	void customTtlIsHonored() {
		JwtService service = new JwtService(
				new JwtProperties(SECRET, Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC));
		UUID userId = UUID.randomUUID();

		String token = service.issueAccessToken(userId);

		assertThat(serviceAt(NOW.plusSeconds(299)).parseUserId(token)).isEqualTo(userId);
		assertThatThrownBy(() -> serviceAt(NOW.plusSeconds(300)).parseUserId(token))
				.isInstanceOf(JwtInvalidException.class);
		assertThat(service.accessTokenExpiresInSeconds()).isEqualTo(300L);
	}

	@Test
	void tokenValidJustBeforeExpiryAndInvalidAtExpiry() {
		JwtService issuer = serviceAt(NOW);
		String token = issuer.issueAccessToken(UUID.randomUUID());

		assertThat(serviceAt(NOW.plusSeconds(899)).parseUserId(token)).isNotNull();
		assertThatThrownBy(() -> serviceAt(NOW.plusSeconds(900)).parseUserId(token))
				.isInstanceOf(JwtInvalidException.class);
		assertThatThrownBy(() -> serviceAt(NOW.plusSeconds(3600)).parseUserId(token))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void wrongKeyIsRejected() {
		String token = serviceAt(NOW).issueAccessToken(UUID.randomUUID());
		JwtService otherKey = new JwtService(
				new JwtProperties(OTHER_SECRET, null), Clock.fixed(NOW, ZoneOffset.UTC));

		assertThatThrownBy(() -> otherKey.parseUserId(token))
				.isInstanceOf(JwtInvalidException.class)
				.hasMessage("Invalid token");
	}

	@Test
	void malformedAndBlankTokensAreRejected() {
		JwtService service = serviceAt(NOW);

		assertThatThrownBy(() -> service.parseUserId("not-a-jwt")).isInstanceOf(JwtInvalidException.class);
		assertThatThrownBy(() -> service.parseUserId("")).isInstanceOf(JwtInvalidException.class);
		assertThatThrownBy(() -> service.parseUserId(null)).isInstanceOf(JwtInvalidException.class);
		assertThatThrownBy(() -> service.parseUserId("a.b")).isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void tamperedPayloadIsRejected() throws Exception {
		JwtService service = serviceAt(NOW);
		String token = service.issueAccessToken(UUID.randomUUID());
		String[] parts = token.split("\\.");
		char first = parts[1].charAt(0);
		String tampered = parts[0] + "." + (first == 'A' ? 'B' : 'A') + parts[1].substring(1)
				+ "." + parts[2];

		assertThatThrownBy(() -> service.parseUserId(tampered))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void missingSubjectIsRejected() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issueTime(Date.from(NOW))
				.expirationTime(Date.from(NOW.plusSeconds(900)))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> serviceAt(NOW).parseUserId(jwt.serialize()))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void nonUuidSubjectIsRejected() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject("user@example.com")
				.issueTime(Date.from(NOW))
				.expirationTime(Date.from(NOW.plusSeconds(900)))
				.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> serviceAt(NOW).parseUserId(jwt.serialize()))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void unsignedTokenIsRejected() {
		// Hand-crafted alg:none token (Nimbus offers no JWSAlgorithm.NONE
		// constant): whether the parser accepts or rejects it, the
		// service must reject it.
		String header = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String payload = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"exp\":"
						+ NOW.plusSeconds(900).getEpochSecond() + "}")
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String unsigned = header + "." + payload + ".";

		assertThatThrownBy(() -> serviceAt(NOW).parseUserId(unsigned))
				.isInstanceOf(JwtInvalidException.class);
	}

	@Test
	void blankMissingOrShortSecretFailsFast() {
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

		assertThatThrownBy(() -> new JwtService(new JwtProperties(null, null), clock))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtService(new JwtProperties("   ", null), clock))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", null), clock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 UTF-8 bytes");
		assertThatThrownBy(() -> new JwtService(null, clock))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new JwtService(new JwtProperties(SECRET, null), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void issueRejectsNullUser() {
		assertThatThrownBy(() -> serviceAt(NOW).issueAccessToken(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
