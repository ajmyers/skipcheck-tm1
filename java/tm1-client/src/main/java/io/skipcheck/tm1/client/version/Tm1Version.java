// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.version;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A TM1 server version, as reported by {@code Server.ProductVersion} (read through
 * {@code Tm1Session.version()}).
 *
 * <p>
 * TM1 reports versions as dot-separated numbers, such as {@code 11.8.03500.6}. Each part
 * is compared numerically, so {@code 11.8.03500.6} equals {@code 11.8.3500.6}, and a
 * shorter version is treated as if padded with zeros: {@code 11.8} equals
 * {@code 11.8.0.0}. {@link #toString()} returns the text exactly as parsed, leading zeros
 * included.
 *
 * <p>
 * This is the TM1 server build number, not the Planning Analytics release number (such as
 * {@code 2.1.19}) or the REST API schema version in {@code $metadata} (such as
 * {@code 11.8.35}).
 */
public final class Tm1Version implements Comparable<Tm1Version> {

	private static final Pattern FORMAT = Pattern.compile("\\d+(\\.\\d+)*");

	private final String text;

	private final int[] parts;

	private Tm1Version(String text, int[] parts) {
		this.text = text;
		this.parts = parts;
	}

	/**
	 * Parses a version such as {@code 11.8.03500.6}. Surrounding whitespace is ignored.
	 * @param text the version text
	 * @return the version
	 * @throws IllegalArgumentException if the text isn't dot-separated numbers
	 */
	public static Tm1Version parse(String text) {
		Objects.requireNonNull(text, "text");
		String trimmed = text.strip();
		if (!FORMAT.matcher(trimmed).matches()) {
			throw new IllegalArgumentException(
					"Not a TM1 version: '" + text + "' (expected numbers separated by dots, like 11.8.03500.6)");
		}
		String[] split = trimmed.split("\\.", -1);
		int[] parts = new int[split.length];
		for (int i = 0; i < split.length; i++) {
			try {
				parts[i] = Integer.parseInt(split[i]);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException(
						"Not a TM1 version: '" + text + "' (part '" + split[i] + "' is too large)", e);
			}
		}
		return new Tm1Version(trimmed, parts);
	}

	/**
	 * True if this version is the same as or newer than {@code other}.
	 * @param other the version to compare with
	 * @return {@code true} if this version is equal to or newer than {@code other}
	 */
	public boolean isAtLeast(Tm1Version other) {
		return compareTo(other) >= 0;
	}

	/**
	 * True if this version is older than {@code other}.
	 * @param other the version to compare with
	 * @return {@code true} if this version is older than {@code other}
	 */
	public boolean isBefore(Tm1Version other) {
		return compareTo(other) < 0;
	}

	@Override
	public int compareTo(Tm1Version other) {
		int length = Math.max(parts.length, other.parts.length);
		for (int i = 0; i < length; i++) {
			int a = i < parts.length ? parts[i] : 0;
			int b = i < other.parts.length ? other.parts[i] : 0;
			if (a != b) {
				return Integer.compare(a, b);
			}
		}
		return 0;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Tm1Version other && compareTo(other) == 0;
	}

	@Override
	public int hashCode() {
		int end = parts.length;
		while (end > 0 && parts[end - 1] == 0) {
			end--;
		}
		return Arrays.hashCode(Arrays.copyOf(parts, end));
	}

	/** The version exactly as parsed, e.g. {@code 11.8.03500.6}. */
	@Override
	public String toString() {
		return text;
	}

}
