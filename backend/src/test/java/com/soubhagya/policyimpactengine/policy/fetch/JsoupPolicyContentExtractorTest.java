package com.soubhagya.policyimpactengine.policy.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for {@link JsoupPolicyContentExtractor} using
 * inline/static HTML fixtures only. No network access.
 */
class JsoupPolicyContentExtractorTest {

	private final PolicyContentExtractor extractor = new JsoupPolicyContentExtractor();

	@Test
	void basicHtmlExtractionReturnsVisibleTextWithoutMarkup() {
		String html = "<html><head><title>Ignored Title</title></head>"
				+ "<body><p>Hello policy reader</p></body></html>";

		String text = extractor.extract(html);

		assertThat(text).contains("Hello policy reader");
		assertThat(text).doesNotContain("<");
		assertThat(text).doesNotContain("Ignored Title");
	}

	@Test
	void headingsAndParagraphsPreserveLineStructure() {
		String html = "<html><body>"
				+ "<h1>Privacy Policy</h1>"
				+ "<p>First paragraph.</p>"
				+ "<p>Second paragraph.</p>"
				+ "</body></html>";

		String text = extractor.extract(html);

		assertThat(lines(text)).containsExactly(
				"Privacy Policy",
				"First paragraph.",
				"Second paragraph.");
	}

	@Test
	void scriptAndStyleElementsAreRemoved() {
		String html = "<html><body>"
				+ "<style>.hidden { display: none; }</style>"
				+ "<script>function track() { alert('x'); }</script>"
				+ "<p>Visible policy text.</p>"
				+ "</body></html>";

		String text = extractor.extract(html);

		assertThat(text).contains("Visible policy text.");
		assertThat(text).doesNotContain("display: none");
		assertThat(text).doesNotContain("function track()");
		assertThat(text).doesNotContain("alert");
	}

	@Test
	void linkTextIsPreservedWithoutUrls() {
		String html = "<html><body>"
				+ "<p>Please <a href=\"https://example.com/contact\">contact us</a> for help.</p>"
				+ "</body></html>";

		String text = extractor.extract(html);

		assertThat(text).contains("contact us");
		assertThat(text).doesNotContain("https://example.com/contact");
	}

	@Test
	void malformedHtmlIsParsedWithoutCrashing() {
		String html = "<html><body><h1>Broken Policy"
				+ "<p>First paragraph<b>bold tail";

		String text = extractor.extract(html);

		assertThat(text).contains("Broken Policy");
		assertThat(text).contains("First paragraph");
		assertThat(text).contains("bold tail");
		assertThat(text).doesNotContain("<");
	}

	@Test
	void emptyAndBlankHtmlReturnEmptyString() {
		assertThat(extractor.extract(null)).isEmpty();
		assertThat(extractor.extract("")).isEmpty();
		assertThat(extractor.extract("   ")).isEmpty();
		assertThat(extractor.extract("<html><head></head><body></body></html>")).isEmpty();
	}

	@Test
	void htmlEntitiesAreDecoded() {
		String html = "<html><body><p>Fish &amp; Chips &lt;seafood&gt; &quot;fresh&quot;</p></body></html>";

		String text = extractor.extract(html);

		assertThat(text).contains("Fish & Chips <seafood> \"fresh\"");
		assertThat(text).doesNotContain("&amp;");
	}

	@Test
	void realisticPrivacyPolicyFixture() {
		String html = """
				<html>
				<head><title>Example Privacy Policy</title>
				<style>body { font-family: sans-serif; }</style>
				<script>window.tracker = true;</script>
				</head>
				<body>
				<h1>Privacy Policy</h1>
				<p>We collect your email address to operate this service.</p>
				<h2>Data Sharing</h2>
				<p>We do not sell personal data. See our <a href="https://example.com/opt-out">opt-out page</a>.</p>
				<ul>
				<li>Location data is never shared.</li>
				<li>Usage data may be aggregated.</li>
				</ul>
				<h2>Contact</h2>
				<p>Email privacy@example.com with questions.</p>
				</body>
				</html>
				""";

		String text = extractor.extract(html);

		assertThat(lines(text)).containsExactly(
				"Privacy Policy",
				"We collect your email address to operate this service.",
				"Data Sharing",
				"We do not sell personal data. See our opt-out page.",
				"Location data is never shared.",
				"Usage data may be aggregated.",
				"Contact",
				"Email privacy@example.com with questions.");
		assertThat(text).doesNotContain("font-family");
		assertThat(text).doesNotContain("window.tracker");
		assertThat(text).doesNotContain("Example Privacy Policy");
	}

	private static List<String> lines(String text) {
		return List.of(text.split("\n"));
	}
}
