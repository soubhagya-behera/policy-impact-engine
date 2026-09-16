package com.soubhagya.policyimpactengine.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.soubhagya.policyimpactengine.diff.domain.PolicyChangeRecord;

/**
 * Phase 2N — pure deterministic keyword/phrase matcher over the canonical
 * persisted change texts (oldText / newText).
 *
 * <p>Plain Java, no Spring/DB/HTTP/clock/randomness. Deterministic: iteration
 * order is fixed (LinkedHashMap, concept-code sorted patterns), regex flags
 * are {@code CASE_INSENSITIVE | UNICODE_CASE | UNICODE_CHARACTER_CLASS}, and
 * evidence is the verbatim {@link Matcher#group()} substring of the source
 * text, never synthesized.
 *
 * <p>One match per (change, concept) at most: if multiple patterns of the
 * same concept fire, or both oldText and newText contain the concept, exactly
 * one row is emitted (the first deterministic hit). This prevents duplicate
 * rows and matches the database unique constraint on (change_id, concept_id).
 *
 * <p>Concept vocabulary is fixed in this slice (9 seeded codes). Patterns are
 * defined here as deterministic regexes; extending the vocabulary is a data +
 * pattern change, not a semantic embedding. No LLM, embeddings or fuzzy logic.
 */
public class DeterministicConceptMatcher implements ConceptMatcher {

	private static final String KIND_KEYWORD = "KEYWORD";
	private static final String KIND_PHRASE = "PHRASE";

	/**
	 * Ordered concept definitions: insertion order is concept-code order for
	 * deterministic iteration. Each concept maps to an ordered list of pattern
	 * descriptors; the first match for that concept wins.
	 */
	private static final Map<String, List<PatternDescriptor>> CONCEPT_PATTERNS = createPatterns();

	private static Map<String, List<PatternDescriptor>> createPatterns() {
		Map<String, List<PatternDescriptor>> map = new LinkedHashMap<>();

		// ADVERTISING
		map.put("ADVERTISING", List.of(
				descriptor("ADVERTISING:advertising", KIND_KEYWORD, "\\badvertis(?:ing|ement|ements)?\\b"),
				descriptor("ADVERTISING:marketing", KIND_KEYWORD, "\\bmarketing\\b"),
				descriptor("ADVERTISING:targeted-ads", KIND_PHRASE, "target(?:ed)?\\s+ads?\\b"),
				descriptor("ADVERTISING:personalised-ads", KIND_PHRASE, "personal(?:ised|ized)\\s+ads?\\b"),
				descriptor("ADVERTISING:ad-network", KIND_PHRASE, "\\bad\\s*networks?\\b"),
				descriptor("ADVERTISING:ad-partners", KIND_PHRASE, "\\bad\\s*partners?\\b"),
				descriptor("ADVERTISING:ad-targeting", KIND_PHRASE, "\\bad\\s*targeting\\b")));

		// ARBITRATION
		map.put("ARBITRATION", List.of(
				descriptor("ARBITRATION:arbitration", KIND_KEYWORD, "\\barbitrat(?:ion|e|ed|ing)\\b"),
				descriptor("ARBITRATION:binding-arbitration", KIND_PHRASE, "binding\\s+arbitration\\b"),
				descriptor("ARBITRATION:class-action-waiver", KIND_PHRASE, "class\\s+action\\s+waiver\\b"),
				descriptor("ARBITRATION:jury-waiver", KIND_PHRASE, "jury\\s+waiver\\b"),
				descriptor("ARBITRATION:dispute-resolution", KIND_PHRASE, "dispute\\s+resolution\\b"),
				descriptor("ARBITRATION:governing-law", KIND_PHRASE, "governing\\s+law\\b")));

		// CHILDREN_DATA
		map.put("CHILDREN_DATA", List.of(
				descriptor("CHILDREN_DATA:children", KIND_KEYWORD, "\\bchildren\\b"),
				descriptor("CHILDREN_DATA:childrens", KIND_KEYWORD, "children's"),
				descriptor("CHILDREN_DATA:child", KIND_KEYWORD, "\\bchild\\b"),
				descriptor("CHILDREN_DATA:coppa", KIND_KEYWORD, "\\bcoppa\\b"),
				descriptor("CHILDREN_DATA:under-13", KIND_PHRASE, "under\\s+13\\b"),
				descriptor("CHILDREN_DATA:under-16", KIND_PHRASE, "under\\s+16\\b"),
				descriptor("CHILDREN_DATA:minor", KIND_KEYWORD, "\\bminors?\\b"),
				descriptor("CHILDREN_DATA:parental-consent", KIND_PHRASE, "parental\\s+consent\\b")));

		// COOKIES
		map.put("COOKIES", List.of(
				descriptor("COOKIES:cookies", KIND_KEYWORD, "\\bcookies?\\b"),
				descriptor("COOKIES:pixel", KIND_KEYWORD, "\\bpixels?\\b"),
				descriptor("COOKIES:web-beacon", KIND_PHRASE, "web\\s+beacons?\\b"),
				descriptor("COOKIES:local-storage", KIND_PHRASE, "local\\s+storage\\b")));

		// DATA_RETENTION
		map.put("DATA_RETENTION", List.of(
				descriptor("DATA_RETENTION:retention", KIND_KEYWORD, "\\bretention\\b"),
				descriptor("DATA_RETENTION:retain", KIND_KEYWORD, "\\bretain(?:ed|s|ing)?\\b"),
				descriptor("DATA_RETENTION:retained-for", KIND_PHRASE, "retain(?:ed|s|ing)?\\s+.*?\\bfor\\b"),
				descriptor("DATA_RETENTION:store-for", KIND_PHRASE, "stor(?:e|ed|ing)\\s+.*?\\bfor\\b"),
				descriptor("DATA_RETENTION:storage-period", KIND_PHRASE, "storage\\s+period\\b"),
				descriptor("DATA_RETENTION:keep-for", KIND_PHRASE, "keep\\s+.*?\\bfor\\b"),
				descriptor("DATA_RETENTION:days-retention", KIND_PHRASE, "\\b\\d+\\s+days\\b")));

		// DELETION_RIGHTS
		map.put("DELETION_RIGHTS", List.of(
				descriptor("DELETION_RIGHTS:deletion", KIND_KEYWORD, "\\bdeletion\\b"),
				descriptor("DELETION_RIGHTS:delete", KIND_KEYWORD, "\\bdelet(?:e|ed|ing)\\b"),
				descriptor("DELETION_RIGHTS:right-to-delete", KIND_PHRASE, "right\\s+to\\s+delet(?:e|ion)\\b"),
				descriptor("DELETION_RIGHTS:right-to-be-forgotten", KIND_PHRASE, "right\\s+to\\s+be\\s+forgotten\\b"),
				descriptor("DELETION_RIGHTS:erasure", KIND_KEYWORD, "\\berasure\\b"),
				descriptor("DELETION_RIGHTS:remove-your-data", KIND_PHRASE, "remove\\s+your\\s+data\\b"),
				descriptor("DELETION_RIGHTS:delete-account", KIND_PHRASE, "delet(?:e|ion)\\s+.*?\\baccount\\b")));

		// LEGAL_BASIS
		map.put("LEGAL_BASIS", List.of(
				descriptor("LEGAL_BASIS:legal-basis", KIND_PHRASE, "legal\\s+basis\\b"),
				descriptor("LEGAL_BASIS:legitimate-interest", KIND_PHRASE, "legitimate\\s+interest\\b"),
				descriptor("LEGAL_BASIS:consent", KIND_KEYWORD, "\\bconsent\\b"),
				descriptor("LEGAL_BASIS:contract-basis", KIND_PHRASE, "performance\\s+of\\s+a\\s+contract\\b"),
				descriptor("LEGAL_BASIS:legal-obligation", KIND_PHRASE, "legal\\s+obligation\\b")));

		// LOCATION
		map.put("LOCATION", List.of(
				descriptor("LOCATION:location", KIND_KEYWORD, "\\blocation\\b"),
				descriptor("LOCATION:geolocation", KIND_KEYWORD, "\\bgeolocation\\b"),
				descriptor("LOCATION:gps", KIND_KEYWORD, "\\bgps\\b"),
				descriptor("LOCATION:latitude", KIND_KEYWORD, "\\blatitude\\b"),
				descriptor("LOCATION:longitude", KIND_KEYWORD, "\\blongitude\\b"),
				descriptor("LOCATION:precise-location", KIND_PHRASE, "precise\\s+location\\b"),
				descriptor("LOCATION:coarse-location", KIND_PHRASE, "coarse\\s+location\\b")));

		// THIRD_PARTY_SHARING
		map.put("THIRD_PARTY_SHARING", List.of(
				descriptor("THIRD_PARTY_SHARING:third-party", KIND_PHRASE, "third\\W*part(?:y|ies)\\b"),
				descriptor("THIRD_PARTY_SHARING:share-with", KIND_PHRASE, "shar(?:e|ed|ing)\\s+with\\b"),
				descriptor("THIRD_PARTY_SHARING:disclosure-to-third", KIND_PHRASE, "disclos(?:e|ed|ing|ure)\\s+.*?\\bthird\\b"),
				descriptor("THIRD_PARTY_SHARING:selling-data", KIND_PHRASE, "sell(?:ing|s)?\\s+.*?\\bdata\\b"),
				descriptor("THIRD_PARTY_SHARING:sale-of-data", KIND_PHRASE, "sale\\s+of\\s+.*?\\bdata\\b")));

		return Map.copyOf(map);
	}

	private static PatternDescriptor descriptor(String patternId, String kind, String regex) {
		Pattern pattern = Pattern.compile(regex,
				Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
		return new PatternDescriptor(patternId, kind, pattern);
	}

	@Override
	public List<ConceptMatch> match(PolicyChangeRecord change) {
		if (change == null) {
			throw new IllegalArgumentException("Change must not be null");
		}
		String oldText = change.getOldText();
		String newText = change.getNewText();

		// Deterministic concept order: sorted codes.
		List<String> orderedCodes = CONCEPT_PATTERNS.keySet().stream().sorted().toList();

		List<ConceptMatch> result = new ArrayList<>();
		for (String code : orderedCodes) {
			List<PatternDescriptor> descriptors = CONCEPT_PATTERNS.get(code);
			ConceptMatch hit = firstMatchForConcept(code, descriptors, oldText, newText);
			if (hit != null) {
				result.add(hit);
			}
		}
		return List.copyOf(result);
	}

	private ConceptMatch firstMatchForConcept(String code, List<PatternDescriptor> descriptors, String oldText,
			String newText) {
		for (PatternDescriptor d : descriptors) {
			// Prefer newText evidence when both contain (deterministic: check newText first).
			String evidence = findEvidence(d.pattern(), newText);
			if (evidence != null) {
				return new ConceptMatch(code, evidence, d.patternId(), d.kind());
			}
			evidence = findEvidence(d.pattern(), oldText);
			if (evidence != null) {
				return new ConceptMatch(code, evidence, d.patternId(), d.kind());
			}
		}
		return null;
	}

	private String findEvidence(Pattern pattern, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		Matcher matcher = pattern.matcher(text);
		if (matcher.find()) {
			return matcher.group();
		}
		return null;
	}

	private record PatternDescriptor(String patternId, String kind, Pattern pattern) {
	}
}
