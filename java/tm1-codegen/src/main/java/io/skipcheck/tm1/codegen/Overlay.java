// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What TM1 does differently from its own {@code $metadata}, read from
 * {@code spec/tm1-overlay.json}: the one file every client's generator reads, so the Java
 * and the other clients encode the same departures.
 *
 * <p>
 * Notes are Markdown; {@link #javadoc(String)} renders one for Javadoc.
 *
 * @param departures properties TM1 sends in another type than declared, by
 * {@code Type/Property}, with a note: they're read as JSON
 * @param lenientDates date-time properties TM1 sometimes sends something else in, by
 * {@code Type/Property}, with a note: they're read as null then
 * @param endpoints notes about endpoints, by coverage-gate ID
 * @param undeclared types for properties the probe found that {@code $metadata} doesn't
 * declare, by {@code Type/Property}, where the kind of value it read isn't enough
 * ({@link Undeclared})
 * @param writeOnly properties TM1 takes in a write but never sends back, by
 * {@code Type/Property}, with a note: the write probe reads them as ignored, so
 * {@link Writes} counts them as written
 * @param writes setters a write type offers though the probe doesn't verify them, by
 * {@code Builder.Setter}, or a whole write type by {@code Builder}, and setters it leaves
 * out though the probe verifies them: why ({@link Offers})
 */
public record Overlay(Map<String, String> departures, Map<String, String> lenientDates,
		Map<String, EndpointNote> endpoints, Map<String, UndeclaredType> undeclared, Map<String, String> writeOnly,
		Map<String, WriteException> writes) {

	private static final Pattern CODE = Pattern.compile("`([^`]*)`");

	/** Copies the maps. */
	public Overlay {
		departures = Collections.unmodifiableMap(new LinkedHashMap<>(departures));
		lenientDates = Collections.unmodifiableMap(new LinkedHashMap<>(lenientDates));
		endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
		undeclared = Collections.unmodifiableMap(new LinkedHashMap<>(undeclared));
		writeOnly = Collections.unmodifiableMap(new LinkedHashMap<>(writeOnly));
		writes = Collections.unmodifiableMap(new LinkedHashMap<>(writes));
	}

	/**
	 * Why a write type offers a setter the probe doesn't verify, or leaves out one it
	 * does.
	 *
	 * @param kind {@code kept}: the probe tried it, and what it found is accounted for
	 * (TM1 transforms it as it should, or it needs a configuration the probe server
	 * lacks); {@code unverified}: the probe can't try it; {@code withheld}: the probe
	 * verifies it, or the overlay keeps its whole write type, but the write type doesn't
	 * offer it, since the only service that takes the write, or TM1, would refuse it
	 * (another setter or method does that write)
	 * @param note why, in Markdown
	 */
	public record WriteException(String kind, String note) {

		/** The kinds. */
		public static final List<String> KINDS = List.of("kept", "unverified", "withheld");

		/**
		 * Checks the kind.
		 * @throws IllegalArgumentException if it isn't one of {@link #KINDS}
		 */
		public WriteException {
			if (!KINDS.contains(kind)) {
				throw new IllegalArgumentException("A write exception is kept, unverified or withheld, not " + kind);
			}
		}

	}

	/**
	 * The type of a property {@code $metadata} doesn't declare.
	 *
	 * @param type its type, such as {@code Collection(Edm.String)}
	 * @param note what's known of it, in Markdown, or null
	 */
	public record UndeclaredType(String type, @Nullable String note) {
	}

	/**
	 * What the overlay says about an endpoint.
	 *
	 * @param note how TM1 behaves, in Markdown, or null
	 * @param forbid generic requests a client mustn't offer for it, such as
	 * {@code delete}
	 */
	public record EndpointNote(@Nullable String note, List<String> forbid) {

		/** Copies the list. */
		public EndpointNote {
			forbid = List.copyOf(forbid);
		}

	}

	/** An overlay that changes nothing. */
	public static final Overlay NONE = new Overlay(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

	/**
	 * The overlay bundled with the generator: {@code spec/tm1-overlay.json}, copied onto
	 * the classpath by the build.
	 * @return the overlay
	 */
	public static Overlay bundled() {
		try (InputStream in = Overlay.class.getResourceAsStream("/tm1-overlay.json")) {
			if (in == null) {
				throw new IllegalStateException("tm1-overlay.json isn't on the classpath");
			}
			return read(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Reads an overlay.
	 * @param in its JSON
	 * @return the overlay
	 */
	public static Overlay read(InputStream in) {
		JsonNode root = JsonMapper.builder().build().readTree(in);
		Map<String, String> departures = new LinkedHashMap<>();
		root.path("departures")
			.properties()
			.forEach(e -> departures.put(e.getKey(), e.getValue().path("note").asString()));
		Map<String, String> lenient = new LinkedHashMap<>();
		root.path("lenientDates")
			.properties()
			.forEach(e -> lenient.put(e.getKey(), e.getValue().path("note").asString()));
		Map<String, EndpointNote> endpoints = new LinkedHashMap<>();
		root.path("endpoints").properties().forEach(e -> {
			JsonNode note = e.getValue().path("note");
			List<String> forbid = new ArrayList<>();
			e.getValue().path("forbid").forEach(f -> forbid.add(f.asString()));
			endpoints.put(e.getKey(), new EndpointNote(note.isString() ? note.asString() : null, forbid));
		});
		Map<String, UndeclaredType> undeclared = new LinkedHashMap<>();
		root.path("undeclared").properties().forEach(e -> {
			JsonNode note = e.getValue().path("note");
			undeclared.put(e.getKey(),
					new UndeclaredType(e.getValue().path("type").asString(), note.isString() ? note.asString() : null));
		});
		Map<String, String> writeOnly = new LinkedHashMap<>();
		root.path("writeOnly")
			.properties()
			.forEach(e -> writeOnly.put(e.getKey(), e.getValue().path("note").asString("")));
		Map<String, WriteException> writes = new LinkedHashMap<>();
		root.path("writes").properties().forEach(e -> {
			List<String> kinds = new ArrayList<>(e.getValue().propertyNames());
			if (kinds.size() != 1) {
				throw new IllegalArgumentException("The overlay's writes entry " + e.getKey()
						+ " needs one of kept, unverified or withheld, with why");
			}
			writes.put(e.getKey(), new WriteException(kinds.get(0), e.getValue().path(kinds.get(0)).asString()));
		});
		return new Overlay(departures, lenient, endpoints, undeclared, writeOnly, writes);
	}

	/**
	 * A Markdown note as Javadoc: {@code `code`} becomes <code>{&#64;code code}</code>.
	 * @param markdown the note
	 * @return the Javadoc text
	 */
	public static String javadoc(String markdown) {
		Matcher m = CODE.matcher(markdown);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(out, Matcher.quoteReplacement("{@code " + m.group(1) + "}"));
		}
		return m.appendTail(out).toString();
	}

}
