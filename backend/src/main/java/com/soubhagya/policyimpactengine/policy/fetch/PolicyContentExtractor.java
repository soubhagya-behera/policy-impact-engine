package com.soubhagya.policyimpactengine.policy.fetch;

/**
 * Application-level abstraction for extracting human-readable text from a
 * fetched policy document.
 *
 * <p>Extraction answers only "what text is present in this HTML document?".
 * It performs no normalization (no whitespace canonicalization beyond what
 * producing text lines strictly requires), no hashing, no versioning, and
 * no pipeline logic. Canonicalization belongs to the later normalization
 * slice.
 *
 * <p>Implementations are plain Java: no repositories, no database, no
 * security, no HTTP, no scheduler, and no user data.
 */
public interface PolicyContentExtractor {

	/**
	 * Extracts visible, human-readable text from the given raw HTML.
	 *
	 * @param html raw HTML document; may be null or blank
	 * @return extracted text; never null (empty string when there is no
	 *         content to extract)
	 */
	String extract(String html);
}
