package com.soubhagya.policyimpactengine.diff;

/**
 * Type of a single textual change between two policy versions.
 */
public enum PolicyChangeType {

	/** Present in the new content with no counterpart in the old content. */
	ADDED,

	/** Present in the old content with no counterpart in the new content. */
	REMOVED,

	/** One old block replaced by one new block at the same position. */
	MODIFIED
}
