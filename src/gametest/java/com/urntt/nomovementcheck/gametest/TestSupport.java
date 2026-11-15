package com.urntt.nomovementcheck.gametest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers shared by the game tests.
 */
final class TestSupport {
	static final Logger LOGGER = LoggerFactory.getLogger("nomovementcheck-gametest");

	private TestSupport() {
	}

	static void check(final boolean condition, final String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
