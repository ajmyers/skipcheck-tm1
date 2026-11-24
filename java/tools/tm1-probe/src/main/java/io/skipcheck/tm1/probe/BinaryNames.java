// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Candidate property names, read from the TM1 server's binary ({@code bin64/tm1s.exe}).
 *
 * <p>
 * TM1 doesn't list the properties its {@code $metadata} leaves out, but its binary holds
 * their names, as text or inside C++ symbols ({@code getUIDataEv}). This reads every run
 * of ASCII letters and digits, as single bytes and as UTF-16LE, splits each run into
 * camel-case words ({@code TM1FormatString}: {@code TM1}, {@code Format},
 * {@code String}), the run whole and split at its digits, and keeps every span of
 * consecutive words that starts with an upper case letter and is 2 to
 * {@value #MAX_LENGTH} characters long. That's far more candidates than names (about
 * 785,000 for TM1 11.8.035); the server decides which are properties
 * ({@link PropertyNameOracle}).
 *
 * <p>
 * The result depends only on the bytes, so the same binary gives the same names on any
 * platform.
 */
public final class BinaryNames {

	/** The longest candidate kept. */
	public static final int MAX_LENGTH = 48;

	private BinaryNames() {
	}

	/**
	 * The candidate names in a binary.
	 * @param bytes the binary
	 * @return the names, sorted, without duplicates
	 */
	public static NavigableSet<String> extract(byte[] bytes) {
		NavigableSet<String> names = new TreeSet<>();
		StringBuilder run = new StringBuilder();
		for (byte b : bytes) {
			if (isAlnum(b)) {
				run.append((char) b);
			}
			else {
				spans(run, names);
				run.setLength(0);
			}
		}
		spans(run, names);
		// UTF-16LE: a letter or digit followed by a zero byte, at either alignment
		for (int start = 0; start < 2; start++) {
			run.setLength(0);
			for (int i = start; i + 1 < bytes.length; i += 2) {
				if (bytes[i + 1] == 0 && isAlnum(bytes[i])) {
					run.append((char) bytes[i]);
				}
				else {
					spans(run, names);
					run.setLength(0);
				}
			}
			spans(run, names);
		}
		return Collections.unmodifiableNavigableSet(names);
	}

	/**
	 * Splits a run of letters and digits into camel-case words. A word starts at an upper
	 * case letter after a lower case letter or digit ({@code getUI}), or at the last of
	 * several upper case letters when a lower case one follows ({@code UIData}).
	 * @param run the run
	 * @return its words, in order
	 */
	static List<String> words(CharSequence run) {
		List<String> words = new ArrayList<>();
		int start = 0;
		for (int j = 1; j < run.length(); j++) {
			char previous = run.charAt(j - 1);
			char c = run.charAt(j);
			boolean nextLower = j + 1 < run.length() && isLower(run.charAt(j + 1));
			if (isUpper(c) && (isLower(previous) || isDigit(previous) || (isUpper(previous) && nextLower))) {
				words.add(run.subSequence(start, j).toString());
				start = j;
			}
		}
		if (start < run.length()) {
			words.add(run.subSequence(start, run.length()).toString());
		}
		return words;
	}

	private static void spans(CharSequence run, NavigableSet<String> names) {
		if (run.length() < 2) {
			return;
		}
		spansOfWords(run, names);
		// C++ symbols put each name's length before it (7Process9getUIData), so the
		// letters
		// between digits are words too
		int start = 0;
		for (int i = 0; i <= run.length(); i++) {
			if (i == run.length() || isDigit(run.charAt(i))) {
				if (start > 0 || i < run.length()) {
					spansOfWords(run.subSequence(start, i), names);
				}
				start = i + 1;
			}
		}
	}

	private static void spansOfWords(CharSequence run, NavigableSet<String> names) {
		if (run.length() < 2) {
			return;
		}
		List<String> words = words(run);
		for (int i = 0; i < words.size(); i++) {
			if (!isUpper(words.get(i).charAt(0))) {
				continue;
			}
			StringBuilder span = new StringBuilder();
			for (int k = i; k < words.size(); k++) {
				span.append(words.get(k));
				if (span.length() > MAX_LENGTH) {
					break;
				}
				if (span.length() >= 2) {
					names.add(span.toString());
				}
			}
		}
	}

	private static boolean isAlnum(byte b) {
		return isUpper((char) b) || isLower((char) b) || isDigit((char) b);
	}

	private static boolean isUpper(char c) {
		return c >= 'A' && c <= 'Z';
	}

	private static boolean isLower(char c) {
		return c >= 'a' && c <= 'z';
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

}
