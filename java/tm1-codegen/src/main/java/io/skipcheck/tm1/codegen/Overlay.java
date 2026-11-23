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
 * What TM1 does differently from its own {@code $metadata} (plan §16.5), read from
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
 */
public record Overlay(Map<String, String> departures, Map<String, String> lenientDates,
		Map<String, EndpointNote> endpoints) {

	private static final Pattern CODE = Pattern.compile("`([^`]*)`");

	/** Copies the maps. */
	public Overlay {
		departures = Collections.unmodifiableMap(new LinkedHashMap<>(departures));
		lenientDates = Collections.unmodifiableMap(new LinkedHashMap<>(lenientDates));
		endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
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
	public static final Overlay NONE = new Overlay(Map.of(), Map.of(), Map.of());

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
		return new Overlay(departures, lenient, endpoints);
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
