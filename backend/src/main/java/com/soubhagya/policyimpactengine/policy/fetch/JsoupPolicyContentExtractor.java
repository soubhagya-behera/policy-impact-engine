package com.soubhagya.policyimpactengine.policy.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Jsoup-based {@link PolicyContentExtractor}.
 *
 * <p>Parses the raw HTML leniently (malformed but parseable documents do
 * not fail), removes {@code script} and {@code style} elements, and collects
 * visible text from the document body in document order — one line per
 * leaf block element (headings, paragraphs, list items, and similar), so
 * basic document structure is preserved. Inline content such as link text
 * is kept; markup, URLs, and head metadata (for example {@code title})
 * are not returned.
 *
 * <p>HTML entities are decoded by the parser as part of producing text.
 * No project-level canonicalization is applied here: whitespace handling
 * is limited to what producing text lines strictly requires
 * (Jsoup's standard text production plus trimming of line edges).
 * Canonical normalization is a later slice.
 *
 * <p>Deterministic: the same HTML input always yields the same output.
 * Plain Java with no Spring, persistence, or I/O dependencies.
 */
public class JsoupPolicyContentExtractor implements PolicyContentExtractor {

	/**
	 * Elements treated as structural blocks. A block element that contains
	 * another block element is traversed rather than emitted, so nested
	 * containers never duplicate the text of their leaf blocks.
	 */
	private static final Set<String> BLOCK_TAGS = Set.of(
			"h1", "h2", "h3", "h4", "h5", "h6",
			"p", "li", "blockquote", "pre", "dt", "dd",
			"div", "section", "article", "main", "header", "footer",
			"aside", "nav", "figure", "figcaption",
			"table", "thead", "tbody", "tfoot", "tr", "td", "th",
			"ul", "ol", "dl");

	@Override
	public String extract(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		Document document = Jsoup.parse(html);
		document.select("script, style").remove();
		Element body = document.body();
		if (body == null) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		collectBlocks(body, lines);
		return String.join("\n", lines);
	}

	private static void collectBlocks(Element parent, List<String> lines) {
		for (Node node : parent.childNodes()) {
			if (node instanceof TextNode textNode) {
				String text = textNode.text().trim();
				if (!text.isEmpty()) {
					lines.add(text);
				}
			}
			else if (node instanceof Element child) {
				if (hasBlockDescendant(child)) {
					collectBlocks(child, lines);
				}
				else {
					String text = child.text().trim();
					if (!text.isEmpty()) {
						lines.add(text);
					}
				}
			}
		}
	}

	private static boolean hasBlockDescendant(Element element) {
		for (Element descendant : element.getAllElements()) {
			if (descendant != element && BLOCK_TAGS.contains(descendant.normalName())) {
				return true;
			}
		}
		return false;
	}
}
