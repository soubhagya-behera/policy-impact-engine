package com.soubhagya.policyimpactengine.audit.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.policyimpactengine.audit.domain.AuditChain;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventRepository;
import com.soubhagya.policyimpactengine.audit.domain.AuditEventType;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult;
import com.soubhagya.policyimpactengine.audit.domain.AuditVerificationResult.FailureReason;

/**
 * Phase 11B — read-only verification of the single global audit
 * chain (see DECISIONS.md ADR-023 §10).
 *
 * <p>Loads one scalar snapshot per row in a single read-only
 * transaction, then walks the chain from the single genesis using
 * {@code prev_hash} linkage only — never timestamps — recomputing
 * every {@code event_hash} through the frozen {@link AuditChain}.
 * No writes, no clock, no security context, no user loading: the
 * actor id travels as a scalar, so the lazy actor association is
 * never initialized.
 *
 * <p>O(N) time and O(N) memory over one bulk read; no repository
 * call happens inside the walk. A VALID verdict means every
 * persisted row is mutually consistent — it does not prove
 * historical completeness against tail truncation, which linkage
 * alone cannot detect (ADR-023 §9).
 *
 * <p>No verification caller exists yet in production code (11C/11D
 * scope); this service is the tested seam those slices build on.
 */
@Service
public class AuditVerificationService {

	private final AuditEventRepository repository;

	public AuditVerificationService(AuditEventRepository repository) {
		if (repository == null) {
			throw new IllegalArgumentException("Repository must not be null");
		}
		this.repository = repository;
	}

	/**
	 * Verifies the whole chain in one read-only transaction.
	 * Never writes; never calls {@code AuditService.append}.
	 */
	@Transactional(readOnly = true)
	public AuditVerificationResult verify() {
		List<ChainRow> rows = repository.findAllForVerification().stream()
				.map(ChainRow::from)
				.toList();
		return verifyChain(rows);
	}

	/**
	 * Immutable scalar snapshot of one persisted row: the eight
	 * canonical fields plus the row id. Metadata travels verbatim
	 * (null stays null); it is never re-serialized.
	 */
	record ChainRow(UUID id, AuditEventType eventType, UUID actorUserId,
			String resourceType, UUID resourceId, Instant occurredAt, String metadata,
			String prevHash, String eventHash) {

		ChainRow {
			if (id == null) {
				throw new IllegalArgumentException("Id must not be null");
			}
			if (eventType == null) {
				throw new IllegalArgumentException("Event type must not be null");
			}
			if (occurredAt == null) {
				throw new IllegalArgumentException("Occurred at must not be null");
			}
			if (eventHash == null) {
				throw new IllegalArgumentException("Event hash must not be null");
			}
		}

		static ChainRow from(AuditEventRepository.AuditVerificationRow row) {
			if (row == null) {
				throw new IllegalArgumentException("Row must not be null");
			}
			return new ChainRow(row.getId(), row.getEventType(), row.getActorUserId(),
					row.getResourceType(), row.getResourceId(), row.getOccurredAt(),
					row.getMetadata(), row.getPrevHash(), row.getEventHash());
		}
	}

	/**
	 * Pure deterministic walk over an in-memory snapshot. Failure
	 * precedence: malformed hashes, genesis structure, the earliest
	 * chain-order failure, then off-chain rows; lowest id breaks
	 * ties wherever no chain position applies.
	 */
	static AuditVerificationResult verifyChain(List<ChainRow> rows) {
		if (rows == null) {
			throw new IllegalArgumentException("Rows must not be null");
		}
		if (rows.isEmpty()) {
			return AuditVerificationResult.valid(0);
		}
		List<ChainRow> byId = rows.stream()
				.sorted(Comparator.comparing(ChainRow::id))
				.toList();

		for (ChainRow row : byId) {
			if (!isHex64(row.eventHash())
					|| (row.prevHash() != null && !isHex64(row.prevHash()))) {
				return AuditVerificationResult.invalid(0, row.id(), null,
						FailureReason.MALFORMED_HASH);
			}
		}

		Map<String, ChainRow> byHash = new HashMap<>();
		for (ChainRow row : byId) {
			if (byHash.putIfAbsent(row.eventHash(), row) != null) {
				return AuditVerificationResult.invalid(0, row.id(), null,
						FailureReason.SHARED_PREDECESSOR);
			}
		}

		List<ChainRow> geneses = byId.stream()
				.filter(row -> row.prevHash() == null)
				.toList();
		if (geneses.isEmpty()) {
			return AuditVerificationResult.invalid(0, byId.get(0).id(), null,
					FailureReason.MISSING_GENESIS);
		}
		if (geneses.size() > 1) {
			return AuditVerificationResult.invalid(0, geneses.get(1).id(), null,
					FailureReason.MULTIPLE_GENESIS);
		}

		Map<String, List<ChainRow>> children = new HashMap<>();
		for (ChainRow row : rows) {
			if (row.prevHash() != null) {
				children.computeIfAbsent(row.prevHash(), key -> new ArrayList<>())
						.add(row);
			}
		}
		children.values().forEach(
				bucket -> bucket.sort(Comparator.comparing(ChainRow::id)));

		Set<UUID> visited = new HashSet<>();
		ChainRow current = geneses.get(0);
		ChainRow previous = null;
		int position = 0;
		while (current != null) {
			if (!visited.add(current.id())) {
				return AuditVerificationResult.invalid(visited.size(), current.id(),
						position, FailureReason.UNREACHABLE_ROW);
			}
			if (position > 0 && !current.prevHash().equals(previous.eventHash())) {
				return AuditVerificationResult.invalid(position, current.id(),
						position, FailureReason.BROKEN_LINK);
			}
			String recomputed = AuditChain.canonicalHash(
					AuditChain.CANONICAL_FORMAT_VERSION, current.eventType().name(),
					current.actorUserId(), current.resourceType(), current.resourceId(),
					current.occurredAt(), current.metadata(), current.prevHash());
			if (!recomputed.equals(current.eventHash())) {
				return AuditVerificationResult.invalid(position, current.id(),
						position, FailureReason.HASH_MISMATCH);
			}
			List<ChainRow> kids = children.getOrDefault(current.eventHash(),
					List.of());
			if (kids.size() > 1) {
				return AuditVerificationResult.invalid(position + 1,
						kids.get(0).id(), position + 1,
						FailureReason.SHARED_PREDECESSOR);
			}
			previous = current;
			current = kids.isEmpty() ? null : kids.get(0);
			position++;
		}

		if (visited.size() != rows.size()) {
			ChainRow first = byId.stream()
					.filter(row -> !visited.contains(row.id()))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException(
							"Unvisited row expected but none found"));
			if (first.prevHash() != null && !byHash.containsKey(first.prevHash())) {
				return AuditVerificationResult.invalid(visited.size(), first.id(),
						null, FailureReason.MISSING_PREDECESSOR);
			}
			return AuditVerificationResult.invalid(visited.size(), first.id(),
					null, FailureReason.UNREACHABLE_ROW);
		}
		return AuditVerificationResult.valid(rows.size());
	}

	private static boolean isHex64(String value) {
		if (value == null || value.length() != 64) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
				return false;
			}
		}
		return true;
	}
}
