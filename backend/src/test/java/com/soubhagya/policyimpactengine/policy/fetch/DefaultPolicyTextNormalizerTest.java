package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link DefaultPolicyTextNormalizer} using
 * inline strings only. No network access.
 */
class DefaultPolicyTextNormalizerTest {

	private final PolicyTextNormalizer normalizer = new DefaultPolicyTextNormalizer();

	@Test
	void nullInputReturnsEmptyString() {
		assertThat(normalizer.normalize(null)).isEmpty();
	}

	@Test
	void emptyInputReturnsEmptyString() {
		assertThat(normalizer.normalize("")).isEmpty();
	}

	@Test
	void whitespaceOnlyInputReturnsEmptyString() {
		assertThat(normalizer.normalize("   \n\t\n  \r\n ")).isEmpty();
	}

	@Test
	void lineEndingStylesAreTreatedConsistently() {
		String lf = "Privacy Policy\nData Sharing\nContact";
		String crlf = "Privacy Policy\r\nData Sharing\r\nContact";
		String cr = "Privacy Policy\rData Sharing\rContact";
		String mixed = "Privacy Policy\r\nData Sharing\rContact\n";

		assertThat(normalizer.normalize(crlf)).isEqualTo(lf);
		assertThat(normalizer.normalize(cr)).isEqualTo(lf);
		assertThat(normalizer.normalize(mixed)).isEqualTo(lf);
	}

	@Test
	void leadingAndTrailingDocumentWhitespaceIsTrimmed() {
		assertThat(normalizer.normalize("\n\n  Privacy Policy  \n\n"))
				.isEqualTo("Privacy Policy");
	}

	@Test
	void repeatedSpacesAndTabsBetweenWordsCollapseToOneSpace() {
		assertThat(normalizer.normalize("We   collect\t\tyour   email."))
				.isEqualTo("We collect your email.");
	}

	@Test
	void repeatedBlankLinesCollapseToOneBlankLine() {
		assertThat(normalizer.normalize("First paragraph.\n\n\n\nSecond paragraph."))
				.isEqualTo("First paragraph.\n\nSecond paragraph.");
	}

	@Test
	void punctuationIsPreserved() {
		String text = "Hello, world! (See section 2.1.) 100% — $5.00; \"quoted\"?";

		assertThat(normalizer.normalize(text)).isEqualTo(text);
	}

	@Test
	void caseIsPreserved() {
		String text = "Privacy POLICY and Data SHARING";

		assertThat(normalizer.normalize(text)).isEqualTo(text);
	}

	@Test
	void wordBoundariesArePreserved() {
		assertThat(normalizer.normalize("hello   world")).isEqualTo("hello world");
		assertThat(normalizer.normalize("a\tb")).isEqualTo("a b");
	}

	@Test
	void meaningfulLineAndParagraphSeparationIsPreserved() {
		assertThat(normalizer.normalize("Line one\nLine two"))
				.isEqualTo("Line one\nLine two");
		assertThat(normalizer.normalize("Paragraph one.\n\nParagraph two."))
				.isEqualTo("Paragraph one.\n\nParagraph two.");
	}

	@Test
	void alreadyNormalizedTextRemainsUnchanged() {
		String canonical = "Privacy Policy\n\nWe collect your email.\nData Sharing";

		assertThat(normalizer.normalize(canonical)).isEqualTo(canonical);
	}

	@Test
	void repeatedNormalizationIsDeterministic() {
		String noisy = "  Privacy   Policy\r\n\r\n\r\nWe\tcollect  your email.\n\n";

		String once = normalizer.normalize(noisy);

		assertThat(normalizer.normalize(once)).isEqualTo(once);
		assertThat(normalizer.normalize(noisy)).isEqualTo(once);
	}

	@Test
	void realisticPrivacyPolicyFixtureNormalizesFormattingNotWording() {
		String noisy = "Privacy Policy\r\n"
				+ "\r\n"
				+ "\r\n"
				+ "We   collect your\temail address to operate this service.   \n"
				+ "Data Sharing\n"
				+ "We do not sell personal data.   See our opt-out page.\n"
				+ "\n"
				+ "\n"
				+ "\n"
				+ "Contact  \n"
				+ "Email privacy@example.com with questions.\n"
				+ "\n";

		assertThat(normalizer.normalize(noisy)).isEqualTo("""
				Privacy Policy

				We collect your email address to operate this service.
				Data Sharing
				We do not sell personal data. See our opt-out page.

				Contact
				Email privacy@example.com with questions.""");
	}
}
