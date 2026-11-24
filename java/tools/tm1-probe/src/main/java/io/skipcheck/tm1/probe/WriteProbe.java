// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEnumType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlStructuredType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What TM1 does with each property in a POST and a PATCH, type by type, as the cases in
 * {@code write-cases.json} describe them.
 *
 * <p>
 * For each case it reads a fixture with every property selected, declared or not
 * ({@code $select=*} leaves some out), and then:
 * <ul>
 * <li><b>{@code postRead}</b>: POSTs that read body back as a create, under a new
 * name;</li>
 * <li><b>{@code minimal}</b> and <b>{@code required}</b>: creates with the case's base
 * and a name, then leaves out each property of the base in turn;</li>
 * <li><b>{@code post}</b> and <b>{@code patch}</b>: per property, a create of the base
 * plus that property, and a PATCH of that property on a fresh object, each read
 * back;</li>
 * <li><b>{@code patchNull}</b>: per property, a PATCH setting it to {@code null};</li>
 * <li><b>{@code rename}</b>: a PATCH of {@code Name}.</li>
 * </ul>
 *
 * <p>
 * An outcome is {@code applied} (read back as sent), {@code ignored} (2xx, read back as
 * the same object reads without it), {@code transformed} (2xx, read back as neither, such
 * as a name-derived {@code UniqueName} or normalised line ends), {@code rejected} (with
 * the status and TM1's error code) or {@code untested} (no value to send: the fixture
 * holds the default, and none can be made up). Values sent come from the case's
 * {@code values}, else the fixture where it differs from the default, else one made up
 * from the property's type.
 *
 * <p>
 * The collection cases PATCH a collection on an object created with some members, and
 * read it back: {@code replaces}, {@code appends}, {@code reorders}, {@code ignored},
 * {@code rejected} or {@code mixed}.
 *
 * <p>
 * Only outcomes, statuses and TM1's error codes are kept, never messages or values, which
 * can hold the names of objects made during the run. So the result depends only on the
 * server.
 */
public final class WriteProbe {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final ProbeClient client;

	private final CsdlDocument metadata;

	private final Map<String, ? extends Map<String, String>> undeclared;

	private final JsonNode cases;

	private final String prefix;

	private final Consumer<String> log;

	private int sequence;

	/**
	 * What the write probe found.
	 *
	 * @param writes per case ID, its outcomes
	 * @param collections per collection case ID, what a PATCH of it does
	 */
	public record Result(NavigableMap<String, JsonNode> writes, NavigableMap<String, JsonNode> collections) {

		/** Copies the maps. */
		public Result {
			writes = Collections.unmodifiableNavigableMap(new TreeMap<>(writes));
			collections = Collections.unmodifiableNavigableMap(new TreeMap<>(collections));
		}

	}

	/**
	 * A write probe of one server, with its fixtures in place.
	 * @param client the server's client
	 * @param metadata the server's {@code $metadata}
	 * @param undeclared the undeclared properties the name probe found, by type, with the
	 * kind of value each holds
	 * @param cases the cases ({@link #bundledCases()})
	 * @param prefix the fixtures' prefix
	 * @param log where progress goes
	 */
	public WriteProbe(ProbeClient client, CsdlDocument metadata, Map<String, ? extends Map<String, String>> undeclared,
			JsonNode cases, String prefix, Consumer<String> log) {
		this.client = client;
		this.metadata = metadata;
		this.undeclared = undeclared;
		this.cases = cases;
		this.prefix = prefix;
		this.log = log;
	}

	/**
	 * The cases bundled with the probe, {@code write-cases.json}.
	 * @return the cases
	 */
	public static JsonNode bundledCases() {
		try (InputStream in = WriteProbe.class.getResourceAsStream("write-cases.json")) {
			if (in == null) {
				throw new IllegalStateException("write-cases.json isn't on the classpath");
			}
			return ProbeClient.JSON.readTree(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Runs every case.
	 * @return what it found
	 */
	public Result run() {
		NavigableMap<String, JsonNode> writes = new TreeMap<>();
		for (JsonNode c : cases.path("cases")) {
			String id = c.path("id").asString();
			writes.put(id, probeCase(c));
			log.accept("writes: " + id);
		}
		NavigableMap<String, JsonNode> collections = new TreeMap<>();
		for (JsonNode c : cases.path("collections")) {
			String id = c.path("id").asString();
			collections.put(id, probeCollection(c));
			log.accept("collections: " + id + " " + collections.get(id));
		}
		return new Result(writes, collections);
	}

	/**
	 * Where a case's objects are made and found: its {@code collection}; its
	 * {@code base}, with {@code {name}} for the try's fresh name; whether the body
	 * carries {@code Name} ({@code named}, by default); the address of what's made
	 * ({@code address}, with {@code {name}}; else {@code collection('name')}; or by the
	 * key TM1 assigns, {@code assigned}, read from the create's answer); its key
	 * properties, which aren't probed ({@code key}); and requests to run before each
	 * create and after each delete ({@code setup}, {@code teardown}, with
	 * {@code {name}}).
	 */
	private final class Shape {

		final String type;

		final String collection;

		final JsonNode base;

		final boolean named;

		final @Nullable String address;

		final @Nullable String assigned;

		final Set<String> keys = new TreeSet<>();

		final JsonNode setup;

		final JsonNode teardown;

		final List<String> names = new ArrayList<>();

		private int next;

		Shape(JsonNode c) {
			type = c.path("type").asString();
			c.path("names").forEach(n -> names.add(n.asString()));
			collection = c.path("collection").asString().replace("{p}", prefix);
			base = c.path("base");
			named = c.path("named").asBoolean(true);
			address = c.path("address").isString() ? c.path("address").asString() : null;
			assigned = c.path("assigned").isString() ? c.path("assigned").asString() : null;
			setup = c.path("setup");
			teardown = c.path("teardown");
			c.path("key").forEach(k -> keys.add(k.asString()));
			if (named) {
				keys.add("Name");
			}
			if (assigned != null) {
				keys.add(assigned);
			}
		}

		/**
		 * Whether TM1 renames one by a PATCH of {@code Name}: only a named one, found by
		 * it.
		 */
		boolean renames() {
			return named && address == null && assigned == null;
		}

		String fresh() {
			// A case's names, in turn, where TM1 takes only some (a locale): else a new
			// one
			String name = names.isEmpty() ? prefix + "W" + String.format(Locale.ROOT, "%03d", sequence++)
					: names.get(next++ % names.size());
			if (assigned == null) {
				client.send("DELETE", addressed(name), null);
			}
			run(teardown, name);
			return name;
		}

		ObjectNode body(String name) {
			ObjectNode body = merge(substitute(base, name), NODES.objectNode());
			if (named) {
				body.put("Name", name);
			}
			return body;
		}

		Made post(String name) {
			return post(name, body(name));
		}

		Made post(String name, ObjectNode body) {
			run(setup, name);
			ProbeClient.Answer answer = client.send("POST", collection, body);
			return new Made(name, answer, answer.ok() ? at(name, answer.body()) : null);
		}

		@Nullable String at(String name, @Nullable JsonNode answer) {
			if (assigned != null) {
				JsonNode key = answer == null ? NODES.missingNode() : answer.path(assigned);
				if (key.isMissingNode() || key.isNull()) {
					return null;
				}
				return collection + "("
						+ (key.isNumber() ? key.asString() : "'" + key.asString().replace("'", "''") + "'") + ")";
			}
			return addressed(name);
		}

		/** Where an object TM1 doesn't assign a key to is found. */
		String addressed(String name) {
			return address != null ? text(address, name) : path(collection, name);
		}

		void remove(Made made) {
			if (made.at() != null) {
				client.send("DELETE", made.at(), null);
			}
			run(teardown, made.name());
		}

		private void run(JsonNode requests, String name) {
			for (JsonNode r : requests) {
				client.send(r.path("method").asString(), text(r.path("path").asString(), name),
						r.has("body") ? substitute(r.path("body"), name) : null);
			}
		}

	}

	/**
	 * What a create made: the try's name, TM1's answer, and where the object is, or null.
	 */
	private record Made(String name, ProbeClient.Answer answer, @Nullable String at) {

		boolean ok() {
			return answer.ok() && at != null;
		}

		/** Where it is: only for one that's {@link #ok}. */
		String where() {
			return Objects.requireNonNull(at, "nothing was made");
		}

	}

	private String text(String template, String name) {
		return template.replace("{p}", prefix).replace("{name}", name);
	}

	private JsonNode probeCase(JsonNode c) {
		if (c.path("singleton").asBoolean(false)) {
			return probeSingleton(c);
		}
		String type = c.path("type").asString();
		Shape shape = new Shape(c);
		JsonNode values = c.path("values");
		ObjectNode result = NODES.objectNode();
		result.put("type", type);
		if (!c.path("generate").asBoolean(true)) {
			// Recorded only: the generators leave it out (#338)
			result.put("generate", false);
		}

		String select = select(type);
		ProbeClient.Answer source = client.get(text(c.path("source").asString(), "") + "?" + select);
		if (!source.ok() || source.body() == null) {
			result.set("source", outcome(source));
			return result;
		}
		// A source can be a list, such as a cube's annotations: its first
		JsonNode first = source.body().path("value").isArray() ? source.body().path("value").path(0) : source.body();
		ObjectNode read = clean(first);

		// The read body as a create
		String name = shape.fresh();
		ObjectNode body = merge(substitute(shape.base, name), read);
		if (shape.named) {
			body.put("Name", name);
		}
		Made made = shape.post(name, body);
		result.set("postRead", outcome(made.answer()));
		shape.remove(made);

		// The least a create needs
		name = shape.fresh();
		Made minimal = shape.post(name);
		result.set("minimal", outcome(minimal.answer()));
		if (!minimal.ok()) {
			shape.remove(minimal);
			return result;
		}
		ObjectNode defaults = readBack(minimal.where(), select);
		shape.remove(minimal);
		ArrayNode required = result.putArray("required");
		Set<String> notRequired = new TreeSet<>();
		c.path("notRequired").forEach(n -> notRequired.add(n.asString()));
		for (String key : sortedFields(shape.base)) {
			// A case's notRequired: what only its own fixture needs (a native view's
			// titles,
			// on a cube with a dimension on no other axis)
			if (key.startsWith("@") || notRequired.contains(key)) {
				continue;
			}
			String n = shape.fresh();
			ObjectNode without = shape.body(n);
			without.remove(key);
			Made m = shape.post(n, without);
			if (!m.answer().ok()) {
				required.add(key);
			}
			shape.remove(m);
		}

		ObjectNode post = result.putObject("post");
		ObjectNode patch = result.putObject("patch");
		ObjectNode patchNull = result.putObject("patchNull");
		// Every property the type has, declared or undeclared, not only what the
		// fixture's read returned: TM1 leaves some out of a read
		for (String property : properties(type, read)) {
			if (shape.keys.contains(property)) {
				continue;
			}
			JsonNode value = valueFor(type, property, values, read.get(property), defaults.get(property));
			if (value == null) {
				post.putObject(property).put("outcome", "untested");
				patch.putObject(property).put("outcome", "untested");
			}
			else {
				post.set(property, tryPost(shape, select, property, value, defaults.get(property)));
				patch.set(property, tryPatch(shape, select, property, value));
			}
			Made m = shape.post(shape.fresh());
			ObjectNode toNull = NODES.objectNode();
			toNull.putNull(property);
			patchNull.set(property, m.ok() ? outcome(client.send("PATCH", m.where(), toNull)) : outcome(m.answer()));
			shape.remove(m);
		}

		probeNested(c, type, shape, read, post, patch);
		probeKeys(type, shape, select, patch);
		probeSecrets(c, shape, post, patch);
		result.set("navigations", probeNavigations(type, shape, c));

		if (shape.renames()) {
			// Renaming by PATCH
			String before = shape.fresh();
			String after = shape.fresh();
			Made m = shape.post(before);
			ObjectNode rename = NODES.objectNode();
			rename.put("Name", after);
			ProbeClient.Answer renamed = client.send("PATCH", path(shape.collection, before), rename);
			boolean oldThere = client.get(path(shape.collection, before)).ok();
			boolean newThere = client.get(path(shape.collection, after)).ok();
			ObjectNode r = renamed.ok() ? NODES.objectNode() : outcome(renamed);
			if (renamed.ok()) {
				r.put("outcome", newThere && !oldThere ? "applied" : oldThere && !newThere ? "ignored" : "transformed");
			}
			result.set("rename", r);
			shape.remove(m);
			delete(shape.collection, after);
		}
		return result;
	}

	/**
	 * A singleton's case ({@code "singleton": true}, such as the server's configuration):
	 * nothing to create, so each property, nested ones by their path
	 * ({@code Administration/Language}), is patched on the one there is, read back,
	 * compared with what it was, and set back. The case's {@code skip} lists paths left
	 * alone, such as what the probe's own connection depends on.
	 */
	private JsonNode probeSingleton(JsonNode c) {
		String type = c.path("type").asString();
		String at = text(c.path("source").asString(), "");
		ObjectNode result = NODES.objectNode();
		result.put("type", type);
		if (!c.path("generate").asBoolean(true)) {
			result.put("generate", false);
		}
		ProbeClient.Answer source = client.get(at);
		if (!source.ok() || source.body() == null) {
			result.set("source", outcome(source));
			return result;
		}
		Set<String> skip = new TreeSet<>();
		c.path("skip").forEach(s -> skip.add(s.asString()));
		probeLeaves(at, type, "", clean(source.body()), c.path("values"), skip, result.putObject("patch"));
		return result;
	}

	private void probeLeaves(String at, String type, String prefixPath, JsonNode current, JsonNode values,
			Set<String> skip, ObjectNode out) {
		for (String property : sortedFields(current)) {
			String here = prefixPath + property;
			if (skip.contains(here)) {
				continue;
			}
			JsonNode original = current.get(property);
			Optional<CsdlProperty> declared = declaredProperty(type, property);
			if (original.isObject() && declared.isPresent()) {
				probeLeaves(at, simple(declared.get().type().name()), here + "/", original, values, skip, out);
				continue;
			}
			JsonNode value = values.has(here) ? substitute(values.get(here), "") : madeUp(type, property, original);
			if (value == null) {
				out.putObject(here).put("outcome", "untested");
				continue;
			}
			ProbeClient.Answer answer = client.send("PATCH", at, nested(here, value));
			if (!answer.ok()) {
				out.set(here, outcome(answer));
				continue;
			}
			ProbeClient.Answer back = client.get(at);
			JsonNode got = back.body() == null ? null : back.body().at("/" + here);
			out.set(here, logged(classify(got == null || got.isMissingNode() ? null : got, value, original), at, here,
					"PATCH", value, got));
			client.send("PATCH", at, nested(here, original));
		}
	}

	/** {@code {"A": {"B": value}}} for the path {@code A/B}. */
	private static ObjectNode nested(String path, JsonNode value) {
		String[] parts = path.split("/", -1);
		ObjectNode root = NODES.objectNode();
		ObjectNode node = root;
		for (int i = 0; i < parts.length - 1; i++) {
			node = node.putObject(parts[i]);
		}
		node.set(parts[parts.length - 1], value);
		return root;
	}

	/**
	 * A create of the base plus one property, read back and compared with what a create
	 * of the base alone, under the same name, reads: so a value derived from the name (a
	 * {@code UniqueName}) reads as ignored, not changed. The create with the property
	 * comes first: TM1 can refuse a name just deleted when it comes back different (an
	 * element of another type, code 272). If the baseline is refused, the default is the
	 * baseline.
	 */
	private JsonNode tryPost(Shape shape, String select, String property, JsonNode value,
			@Nullable JsonNode defaultValue) {
		List<String> navs = carried(shape.type, property);
		if (!navs.isEmpty()) {
			return tryPostLinks(shape, property, value, navs);
		}
		String name = shape.fresh();
		ObjectNode body = shape.body(name);
		body.set(property, value);
		Made made = shape.post(name, body);
		JsonNode got = made.ok() ? readBack(made.where(), select).get(property) : null;
		shape.remove(made);
		if (!made.answer().ok()) {
			return outcome(made.answer());
		}
		JsonNode baseline = defaultValue;
		Made again = shape.post(name);
		if (again.ok()) {
			baseline = readBack(again.where(), select).get(property);
		}
		shape.remove(again);
		return logged(classify(got, value, baseline), shape.collection, property, "POST", value, got);
	}

	/** A PATCH of one property on a fresh object, compared with what it read before. */
	private JsonNode tryPatch(Shape shape, String select, String property, JsonNode value) {
		List<String> navs = carried(shape.type, property);
		if (!navs.isEmpty()) {
			return tryPatchLinks(shape, property, value, navs);
		}
		Made made = shape.post(shape.fresh());
		if (!made.ok()) {
			shape.remove(made);
			return outcome(made.answer());
		}
		JsonNode baseline = readBack(made.where(), select).get(property);
		ObjectNode body = NODES.objectNode();
		body.set(property, value);
		ProbeClient.Answer answer = client.send("PATCH", made.where(), body);
		JsonNode got = answer.ok() ? readBack(made.where(), select).get(property) : null;
		JsonNode result = answer.ok()
				? logged(classify(got, value, baseline), shape.collection, property, "PATCH", value, got)
				: outcome(answer);
		shape.remove(made);
		return result;
	}

	/**
	 * The navigations a property's structured value carries (a view axis's
	 * {@code Subset}, a title's {@code Selected}): a value like that reads back without
	 * them unless they're expanded, so it's compared by what they point at.
	 */
	private List<String> carried(String type, String property) {
		Optional<CsdlProperty> declared = declaredProperty(type, property);
		if (declared.isEmpty() || declared.get().type().isPrimitive()) {
			return List.of();
		}
		List<String> navs = new ArrayList<>();
		for (CsdlStructuredType level : metadata.typeHierarchy(metadata.qualify(declared.get().type().name()))) {
			level.navigationProperties().forEach(n -> navs.add(n.name()));
		}
		return navs;
	}

	/**
	 * Per member, what each navigation it carries points at, as read with them expanded.
	 */
	private @Nullable List<Map<String, String>> links(String at, String property, List<String> navs) {
		StringBuilder expand = new StringBuilder();
		for (String nav : navs) {
			expand.append(expand.isEmpty() ? "" : ",")
				.append(property)
				.append('/')
				.append(nav)
				.append("($select=Name)");
		}
		ProbeClient.Answer answer = client.get(at + "?$select=" + property + "&$expand=" + expand);
		if (!answer.ok() || answer.body() == null) {
			return null;
		}
		JsonNode value = answer.body().path(property);
		List<Map<String, String>> out = new ArrayList<>();
		for (JsonNode member : value.isArray() ? value : NODES.arrayNode().add(value)) {
			Map<String, String> keys = new TreeMap<>();
			for (String nav : navs) {
				if (member.path(nav).isObject()) {
					keys.put(nav, memberKey(member.path(nav)));
				}
			}
			out.add(keys);
		}
		return out;
	}

	/**
	 * Per member, what each navigation sent points at: a binding's key, or a nested
	 * entity's Name.
	 */
	private static List<Map<String, String>> sentLinks(JsonNode value, List<String> navs) {
		List<Map<String, String>> out = new ArrayList<>();
		for (JsonNode member : value.isArray() ? value : NODES.arrayNode().add(value)) {
			Map<String, String> keys = new TreeMap<>();
			for (String nav : navs) {
				if (member.path(nav + "@odata.bind").isString()) {
					keys.put(nav, keyOf(member.path(nav + "@odata.bind").asString()));
				}
				else if (member.path(nav).path("Name").isString()) {
					keys.put(nav, member.path(nav).path("Name").asString());
				}
			}
			out.add(keys);
		}
		return out;
	}

	/**
	 * Applied when every navigation sent points where it was sent to (one nested without
	 * a name is counted only), ignored when nothing changed, else transformed.
	 */
	private static JsonNode linkOutcome(@Nullable List<Map<String, String>> got,
			@Nullable List<Map<String, String>> baseline, List<Map<String, String>> sent) {
		ObjectNode result = NODES.objectNode();
		if (got == null) {
			return result.put("outcome", "unread");
		}
		if (got.equals(baseline)) {
			return result.put("outcome", "ignored");
		}
		boolean matches = got.size() == sent.size();
		for (int i = 0; matches && i < sent.size(); i++) {
			for (Map.Entry<String, String> e : sent.get(i).entrySet()) {
				matches &= e.getValue().equals(got.get(i).get(e.getKey()));
			}
		}
		return result.put("outcome", matches ? "applied" : "transformed");
	}

	private JsonNode tryPostLinks(Shape shape, String property, JsonNode value, List<String> navs) {
		String name = shape.fresh();
		ObjectNode body = shape.body(name);
		body.set(property, value);
		Made made = shape.post(name, body);
		List<Map<String, String>> got = made.ok() ? links(made.where(), property, navs) : null;
		shape.remove(made);
		if (!made.answer().ok()) {
			return outcome(made.answer());
		}
		Made again = shape.post(name);
		List<Map<String, String>> baseline = again.ok() ? links(again.where(), property, navs) : null;
		shape.remove(again);
		return linkOutcome(got, baseline, sentLinks(value, navs));
	}

	private JsonNode tryPatchLinks(Shape shape, String property, JsonNode value, List<String> navs) {
		Made made = shape.post(shape.fresh());
		if (!made.ok()) {
			shape.remove(made);
			return outcome(made.answer());
		}
		List<Map<String, String>> baseline = links(made.where(), property, navs);
		ObjectNode body = NODES.objectNode();
		body.set(property, value);
		ProbeClient.Answer answer = client.send("PATCH", made.where(), body);
		List<Map<String, String>> got = answer.ok() ? links(made.where(), property, navs) : null;
		shape.remove(made);
		return answer.ok() ? linkOutcome(got, baseline, sentLinks(value, navs)) : outcome(answer);
	}

	/**
	 * A transformed write, logged with what was sent and what came back, for a person to
	 * judge; the report keeps the outcome only, as a value read back (a timestamp) can
	 * differ run to run.
	 */
	private JsonNode logged(JsonNode result, String collection, String property, String method, JsonNode sent,
			@Nullable JsonNode got) {
		if (result.path("outcome").asString().equals("transformed")) {
			log.accept(
					"transformed: " + method + " " + collection + " " + property + ": sent " + sent + ", read " + got);
		}
		return result;
	}

	/**
	 * The fields of every structured value the type carries: a complex property
	 * ({@code Parameters}, {@code DataSource}) or a nested navigation in the case's base
	 * (an annotation's {@code ApplicationContext}). Starting from the fixture's value
	 * (else the base's, else the case's {@code values}), one field of its first member at
	 * a time is changed, sent in a create and a PATCH, read back, and compared, by path:
	 * {@code Parameters/Prompt}; a field that's a navigation is bound and read back
	 * expanded ({@code Columns/Subset@odata.bind}).
	 */
	private void probeNested(JsonNode c, String type, Shape shape, JsonNode read, ObjectNode post, ObjectNode patch) {
		for (Holder holder : holders(type, shape, read, c.path("values"))) {
			String element = holder.elementType();
			JsonNode start = holder.start();
			JsonNode first = start.isArray() ? start.path(0) : start;
			// Declared fields, and an open type's undeclared ones the value holds
			TreeSet<String> fields = new TreeSet<>();
			for (CsdlStructuredType level : metadata.typeHierarchy(qualified(element))) {
				level.properties().forEach(p -> fields.add(p.name()));
			}
			first.propertyNames().forEach(f -> {
				if (!f.contains("@")) {
					fields.add(f);
				}
			});
			for (String field : fields) {
				Optional<CsdlProperty> declared = declaredProperty(element, field);
				if (declared.isPresent() && !declared.get().type().isPrimitive()
						&& metadata.enumType(metadata.qualify(declared.get().type().name())).isEmpty()) {
					continue;
				}
				String path = holder.property() + "/" + field;
				// A value the case gives by path, else one made up from what's held
				JsonNode given = c.path("values").has(path) ? substitute(c.path("values").path(path), "") : null;
				JsonNode changed;
				JsonNode value;
				if (given != null && given.path("$member").isObject()) {
					// A field that only changes with others (a data source's Type and its
					// settings): the whole first member, replaced
					value = given.path("$member").get(field);
					changed = withMember(start, given.path("$member"));
				}
				else {
					value = given != null ? given : madeUp(element, field, first.get(field));
					changed = value == null ? null : with(start, field, value);
				}
				if (value == null || changed == null) {
					post.putObject(path).put("outcome", "untested");
					patch.putObject(path).put("outcome", "untested");
					continue;
				}
				post.set(path, nestedPost(shape, holder, start, changed, field, value, null, false));
				patch.set(path, nestedPatch(shape, holder, start, changed, field, value, null, false));
			}
			for (CsdlStructuredType level : metadata.typeHierarchy(qualified(element))) {
				for (CsdlNavigationProperty nav : level.navigationProperties()) {
					// The case's value for the path (a target fitting this holder: a
					// subset
					// of the dimension on this axis), else the cases' target for the type
					String path = holder.property() + "/" + nav.name() + "@odata.bind";
					String target = c.path("values").path(path).isString()
							? text(c.path("values").path(path).asString(), "")
							: target(cases.path("targets").path(simple(nav.type().name())));
					String nestedPath = holder.property() + "/" + nav.name();
					JsonNode nestedValue = c.path("values").path(nestedPath);
					if (nestedValue.isObject()) {
						JsonNode sent = substitute(nestedValue, "");
						JsonNode withNested = withNav(start, nav.name(), sent);
						post.set(nestedPath,
								nestedPost(shape, holder, start, withNested, nav.name(), sent, null, true));
						patch.set(nestedPath,
								nestedPatch(shape, holder, start, withNested, nav.name(), sent, null, true));
					}
					else {
						post.putObject(nestedPath).put("outcome", "untested");
						patch.putObject(nestedPath).put("outcome", "untested");
					}
					if (target == null) {
						post.putObject(path).put("outcome", "untested");
						patch.putObject(path).put("outcome", "untested");
						continue;
					}
					ObjectNode bound = NODES.objectNode();
					bound.put(nav.name() + "@odata.bind", target);
					JsonNode changed = withBinding(start, nav.name(), bound);
					post.set(path, nestedPost(shape, holder, start, changed, nav.name(), null, keyOf(target), false));
					patch.set(path, nestedPatch(shape, holder, start, changed, nav.name(), null, keyOf(target), false));
				}
			}
		}
	}

	/**
	 * A structured value a type carries: the property, whether it's a navigation, the
	 * element type, and the value to start from.
	 */
	private record Holder(String property, boolean navigation, String elementType, JsonNode start) {
	}

	private List<Holder> holders(String type, Shape shape, JsonNode read, JsonNode values) {
		List<Holder> holders = new ArrayList<>();
		JsonNode base = substitute(shape.base, "");
		for (CsdlStructuredType level : metadata.typeHierarchy(qualified(type))) {
			for (CsdlProperty p : level.properties()) {
				if (p.type().isPrimitive() || metadata.enumType(metadata.qualify(p.type().name())).isPresent()) {
					continue;
				}
				// The base's value first, which a create takes; else the fixture's, which
				// can read back without what a navigation in it holds (a view's axes)
				JsonNode start = structured(base.get(p.name()));
				if (start == null) {
					start = structured(read.get(p.name()));
				}
				if (start == null) {
					start = structured(substitute(values.path(p.name()), ""));
				}
				if (start != null) {
					holders.add(new Holder(p.name(), false, simple(p.type().name()), start));
				}
			}
			for (CsdlNavigationProperty n : level.navigationProperties()) {
				JsonNode start = structured(base.get(n.name()));
				if (start != null) {
					holders.add(new Holder(n.name(), true, simple(n.type().name()), start));
				}
			}
		}
		holders.sort(Comparator.comparing(Holder::property));
		return holders;
	}

	/** A value to change a field of: an object, or a list whose first member is one. */
	private static @Nullable JsonNode structured(@Nullable JsonNode value) {
		if (value == null) {
			return null;
		}
		if (value.isObject() && !value.isEmpty()) {
			return clean(value);
		}
		if (value.isArray() && value.size() > 0 && value.path(0).isObject()) {
			ArrayNode out = NODES.arrayNode();
			value.forEach(v -> out.add(v.isObject() ? stripLinks(v) : v));
			return out;
		}
		return null;
	}

	/** An object without its annotations, but keeping its bindings. */
	private static ObjectNode stripLinks(JsonNode v) {
		ObjectNode out = NODES.objectNode();
		v.properties().forEach(e -> {
			if (!e.getKey().startsWith("@")) {
				out.set(e.getKey(), e.getValue().deepCopy());
			}
		});
		return out;
	}

	/** The value with its first member replaced. */
	private static JsonNode withMember(JsonNode start, JsonNode member) {
		JsonNode copy = start.deepCopy();
		if (copy.isArray()) {
			((ArrayNode) copy).set(0, member.deepCopy());
			return copy;
		}
		return member.deepCopy();
	}

	/**
	 * The value with its first member's navigation nested instead (a binding dropped).
	 */
	private static JsonNode withNav(JsonNode start, String nav, JsonNode nested) {
		JsonNode copy = start.deepCopy();
		ObjectNode first = (ObjectNode) (copy.isArray() ? copy.get(0) : copy);
		first.remove(nav + "@odata.bind");
		first.set(nav, nested.deepCopy());
		return copy;
	}

	/** The value with its first member's field set. */
	private static JsonNode with(JsonNode start, String field, JsonNode value) {
		JsonNode copy = start.deepCopy();
		ObjectNode first = (ObjectNode) (copy.isArray() ? copy.get(0) : copy);
		first.set(field, value);
		return copy;
	}

	/**
	 * The value with its first member's navigation bound instead (nested form dropped).
	 */
	private static JsonNode withBinding(JsonNode start, String nav, ObjectNode bound) {
		JsonNode copy = start.deepCopy();
		ObjectNode first = (ObjectNode) (copy.isArray() ? copy.get(0) : copy);
		first.remove(nav);
		first.remove(nav + "@odata.bind");
		first.setAll(bound);
		return copy;
	}

	/** The structured value as read back: its first member, navigations expanded. */
	private @Nullable JsonNode readNested(String at, Holder holder, @Nullable String nav, boolean whole) {
		// A navigation expanded: by its name, or in full to compare a nested entity sent
		String expand = nav == null ? "" : whole ? nav : nav + "($select=Name)";
		ProbeClient.Answer answer;
		JsonNode value;
		if (holder.navigation()) {
			answer = client.get(at + "/" + holder.property() + (expand.isEmpty() ? "" : "?$expand=" + expand));
			value = answer.body() == null ? null : answer.body().path("value");
		}
		else {
			answer = client.get(at + "?$select=" + holder.property()
					+ (expand.isEmpty() ? "" : "&$expand=" + holder.property() + "/" + expand));
			value = answer.body() == null ? null : answer.body().get(holder.property());
		}
		if (!answer.ok() || value == null) {
			return null;
		}
		return value.isArray() ? value.get(0) : value;
	}

	private JsonNode nestedPost(Shape shape, Holder holder, JsonNode start, JsonNode changed, String field,
			@Nullable JsonNode value, @Nullable String key, boolean whole) {
		String name = shape.fresh();
		ObjectNode body = shape.body(name);
		body.set(holder.property(), changed);
		Made made = shape.post(name, body);
		JsonNode got = made.ok() ? readNested(made.where(), holder, key == null && !whole ? null : field, whole) : null;
		shape.remove(made);
		if (!made.answer().ok()) {
			return outcome(made.answer());
		}
		ObjectNode baselineBody = shape.body(name);
		baselineBody.set(holder.property(), start);
		Made again = shape.post(name, baselineBody);
		JsonNode baseline = again.ok() ? readNested(again.where(), holder, key == null && !whole ? null : field, whole)
				: null;
		shape.remove(again);
		return nestedOutcome(got, baseline, field, value, key, shape.collection + " " + holder.property(), "POST",
				whole);
	}

	private JsonNode nestedPatch(Shape shape, Holder holder, JsonNode start, JsonNode changed, String field,
			@Nullable JsonNode value, @Nullable String key, boolean whole) {
		String name = shape.fresh();
		ObjectNode body = shape.body(name);
		body.set(holder.property(), start);
		Made made = shape.post(name, body);
		if (!made.ok()) {
			shape.remove(made);
			return outcome(made.answer());
		}
		JsonNode baseline = readNested(made.where(), holder, key == null && !whole ? null : field, whole);
		ObjectNode change = NODES.objectNode();
		change.set(holder.property(), changed);
		ProbeClient.Answer answer = client.send("PATCH", made.where(), change);
		JsonNode got = answer.ok() ? readNested(made.where(), holder, key == null && !whole ? null : field, whole)
				: null;
		shape.remove(made);
		if (!answer.ok()) {
			return outcome(answer);
		}
		return nestedOutcome(got, baseline, field, value, key, shape.collection + " " + holder.property(), "PATCH",
				whole);
	}

	private JsonNode nestedOutcome(@Nullable JsonNode got, @Nullable JsonNode baseline, String field,
			@Nullable JsonNode value, @Nullable String key, String where, String method, boolean whole) {
		ObjectNode result = NODES.objectNode();
		if (got == null) {
			return result.put("outcome", "unread");
		}
		if (whole && value != null) {
			// A nested entity: what it holds of what was sent, as read expanded
			JsonNode now = got.path(field);
			JsonNode was = baseline == null ? null : baseline.path(field);
			return result.put("outcome", contains(now, value) ? "applied" : same(now, was) ? "ignored" : "transformed");
		}
		if (key != null) {
			String now = got.path(field).isObject() ? memberKey(got.path(field)) : null;
			String was = baseline != null && baseline.path(field).isObject() ? memberKey(baseline.path(field)) : null;
			return result.put("outcome",
					key.equals(now) ? "applied" : Objects.equals(now, was) ? "ignored" : "transformed");
		}
		return logged(
				classify(got.get(field), value == null ? NODES.nullNode() : value,
						baseline == null ? null : baseline.get(field)),
				where, field, method, value == null ? NODES.nullNode() : value, got.get(field));
	}

	/**
	 * The case's keys other than {@code Name} (a rename has its own try): each changed in
	 * a PATCH of a fresh object, which is then looked for where it was. {@code ignored}
	 * when it's still there with the key it had, {@code applied} when it isn't, or
	 * {@code rejected}.
	 */
	private void probeKeys(String type, Shape shape, String select, ObjectNode patch) {
		for (String key : shape.keys) {
			// A rename has its own try, where the object is found by its name
			if (key.equals("Name") && shape.renames()) {
				continue;
			}
			Made made = shape.post(shape.fresh());
			if (!made.ok()) {
				patch.set(key, outcome(made.answer()));
				shape.remove(made);
				continue;
			}
			JsonNode before = readBack(made.where(), select).get(key);
			JsonNode value = madeUp(type, key, before);
			if (value == null) {
				value = NODES.stringNode("Probe");
			}
			ObjectNode body = NODES.objectNode();
			body.set(key, value);
			ProbeClient.Answer answer = client.send("PATCH", made.where(), body);
			ObjectNode result = NODES.objectNode();
			if (!answer.ok()) {
				patch.set(key, outcome(answer));
			}
			else {
				JsonNode after = readBack(made.where(), select).get(key);
				result.put("outcome", after != null && same(after, before) ? "ignored" : "applied");
				patch.set(key, result);
			}
			shape.remove(made);
		}
	}

	/**
	 * The case's secrets ({@code "secrets": {"Password": ["one", "two"]}}): properties
	 * TM1 never reads back, checked by their effect instead. A create with the first, and
	 * a PATCH to the second, is applied when the object then signs in with it and not
	 * with the other ({@link ProbeClient#signsIn}).
	 */
	private void probeSecrets(JsonNode c, Shape shape, ObjectNode post, ObjectNode patch) {
		for (Map.Entry<String, JsonNode> secret : c.path("secrets").properties()) {
			String property = secret.getKey();
			String first = secret.getValue().path(0).asString();
			String second = secret.getValue().path(1).asString();
			String name = shape.fresh();
			ObjectNode body = shape.body(name);
			body.put(property, first);
			Made created = shape.post(name, body);
			post.set(property, created.answer().ok() ? signedIn(name, first, second) : outcome(created.answer()));
			shape.remove(created);

			Made made = shape.post(shape.fresh());
			ObjectNode change = NODES.objectNode();
			change.put(property, second);
			ProbeClient.Answer patched = made.ok() ? client.send("PATCH", made.where(), change) : made.answer();
			patch.set(property, patched.ok() ? signedIn(made.name(), second, first) : outcome(patched));
			shape.remove(made);
		}
	}

	/**
	 * Every navigation of the type, in a create and a PATCH, bound to a fixture (the
	 * cases' {@code targets}, by entity type) and nested (the target type's own case,
	 * under a fresh name, deleted after), each read back by {@code GET …/Navigation} and
	 * compared with what the object held without it: {@code applied}, {@code appends},
	 * {@code replaces}, {@code ignored}, {@code transformed}, {@code mixed},
	 * {@code rejected}, {@code unread} (TM1 took it but won't list the navigation), or
	 * {@code untested} (nothing to bind or nest).
	 */
	private ObjectNode probeNavigations(String type, Shape shape, JsonNode c) {
		List<CsdlNavigationProperty> navigations = new ArrayList<>();
		for (CsdlStructuredType level : metadata.typeHierarchy(qualified(type))) {
			navigations.addAll(level.navigationProperties());
		}
		navigations.sort(Comparator.comparing(CsdlNavigationProperty::name));
		ObjectNode out = NODES.objectNode();
		for (CsdlNavigationProperty navigation : navigations) {
			String nav = navigation.name();
			String target = simple(navigation.type().name());
			boolean many = navigation.type().collection();
			ObjectNode n = out.putObject(nav);
			ObjectNode post = n.putObject("post");
			ObjectNode patch = n.putObject("patch");
			// The case's own target for a type, else the cases'
			JsonNode targets = c.path("targets").has(target) ? c.path("targets") : cases.path("targets");
			String bound = target(targets.path(target));
			if (bound == null) {
				post.putObject("bind").put("outcome", "untested");
				patch.putObject("bind").put("outcome", "untested");
			}
			else {
				// Added to what the base binds already (a cube's dimensions), not in
				// place of it
				JsonNode value = NODES.stringNode(bound);
				if (many) {
					ArrayNode all = NODES.arrayNode();
					substitute(shape.base.path(nav + "@odata.bind"), "").forEach(all::add);
					value = all.add(bound);
				}
				Sent bind = new Sent(nav + "@odata.bind", value, keyOf(bound), null);
				post.set("bind", navPost(shape, nav, () -> bind));
				patch.set("bind", navPatch(shape, nav, () -> bind));
			}
			JsonNode nestBody = cases.path("nest").path(target);
			JsonNode nestedCase = caseFor(target);
			if (nestBody.isObject()) {
				// A body to nest, for a type without a case to make one from
				Supplier<Sent> nestedBody = () -> {
					JsonNode made = substitute(nestBody, prefix + "N" + String.format(Locale.ROOT, "%03d", sequence++));
					return new Sent(nav, many ? NODES.arrayNode().add(made) : made, bodyKey(made), null);
				};
				post.set("nested", navPost(shape, nav, nestedBody));
				patch.set("nested", navPatch(shape, nav, nestedBody));
			}
			else if (nestedCase == null || !nestedCase.path("named").asBoolean(true)) {
				post.putObject("nested").put("outcome", "untested");
				patch.putObject("nested").put("outcome", "untested");
			}
			else {
				Supplier<Sent> nested = () -> {
					String into = text(nestedCase.path("collection").asString(), "");
					String name = fresh(into);
					ObjectNode body = withName(substitute(nestedCase.path("base"), name), name);
					return new Sent(nav, many ? NODES.arrayNode().add(body) : body, name, path(into, name));
				};
				post.set("nested", navPost(shape, nav, nested));
				patch.set("nested", navPatch(shape, nav, nested));
			}
		}
		return out;
	}

	/**
	 * What a navigation write sends: the property, its value, the key the navigation
	 * should then hold, and what to delete after (a nested entity), or null.
	 */
	private record Sent(String property, JsonNode value, @Nullable String key, @Nullable String cleanup) {
	}

	/**
	 * A binding target: a path, or {@code {"from": path, "key": field, "path":
	 * template}}, found at run time (a key TM1 assigns: a session's ID); with no
	 * {@code key}, the first member's {@code @odata.id}. Null when there's none, or
	 * nothing to find.
	 */
	private @Nullable String target(JsonNode spec) {
		if (spec.isString()) {
			return text(spec.asString(), "");
		}
		if (!spec.isObject()) {
			return null;
		}
		ProbeClient.Answer found = client.get(text(spec.path("from").asString(), ""));
		if (!found.ok() || found.body() == null) {
			return null;
		}
		JsonNode first = found.body().path("value").isArray() ? found.body().path("value").path(0) : found.body();
		if (!spec.has("key")) {
			return first.path("@odata.id").isString() ? first.path("@odata.id").asString() : null;
		}
		JsonNode key = first.path(spec.path("key").asString());
		if (key.isMissingNode() || key.isNull()) {
			return null;
		}
		return text(spec.path("path").asString(), "").replace("{key}", key.asString());
	}

	/** The key a nested body's entity is listed by, or null to count any change. */
	private static @Nullable String bodyKey(JsonNode body) {
		for (String field : KEYS) {
			if (body.path(field).isString()) {
				return body.path(field).asString();
			}
		}
		return null;
	}

	/** What a navigation's members are listed by, in this order. */
	private static final List<String> KEYS = List.of("Name", "ID", "LocaleID", "ComponentName", "Step", "Timestamp");

	private JsonNode navPost(Shape shape, String nav, Supplier<Sent> make) {
		String name = shape.fresh();
		Sent sent = make.get();
		ObjectNode body = shape.body(name);
		body.set(sent.property(), sent.value());
		Made made = shape.post(name, body);
		List<String> after = made.ok() ? navKeys(made.where(), nav) : null;
		shape.remove(made);
		cleanup(sent);
		if (!made.answer().ok()) {
			return outcome(made.answer());
		}
		List<String> baseline = null;
		Made again = shape.post(name);
		if (again.ok()) {
			baseline = navKeys(again.where(), nav);
		}
		shape.remove(again);
		ObjectNode result = NODES.objectNode();
		if (after == null || baseline == null) {
			return result.put("outcome", "unread");
		}
		return result.put("outcome", after.equals(baseline) ? "ignored"
				: sent.key() == null || after.contains(sent.key()) ? "applied" : "transformed");
	}

	private JsonNode navPatch(Shape shape, String nav, Supplier<Sent> make) {
		Made made = shape.post(shape.fresh());
		if (!made.ok()) {
			shape.remove(made);
			return outcome(made.answer());
		}
		List<String> before = navKeys(made.where(), nav);
		Sent sent = make.get();
		ObjectNode body = NODES.objectNode();
		body.set(sent.property(), sent.value());
		ProbeClient.Answer answer = client.send("PATCH", made.where(), body);
		List<String> after = answer.ok() ? navKeys(made.where(), nav) : null;
		shape.remove(made);
		cleanup(sent);
		if (!answer.ok()) {
			return outcome(answer);
		}
		ObjectNode result = NODES.objectNode();
		if (after == null || before == null) {
			return result.put("outcome", "unread");
		}
		String outcome;
		if (after.equals(before)) {
			outcome = "ignored";
		}
		else if (sent.key() != null && !after.contains(sent.key())) {
			outcome = "transformed";
		}
		else if (before.isEmpty()) {
			outcome = "applied";
		}
		else if (after.containsAll(before)) {
			outcome = "appends";
		}
		else if (Collections.disjoint(after, before)) {
			outcome = "replaces";
		}
		else {
			outcome = "mixed";
		}
		return result.put("outcome", outcome);
	}

	private void cleanup(Sent sent) {
		if (sent.cleanup() != null) {
			client.send("DELETE", sent.cleanup(), null);
		}
	}

	/**
	 * The keys a navigation holds: each member's {@code Name}, else its {@code ID}, in
	 * order; none when a single-valued one is unset; null when TM1 won't list it.
	 */
	private @Nullable List<String> navKeys(String at, String nav) {
		ProbeClient.Answer answer = client.get(at + "/" + nav);
		if (answer.status() == 204 || answer.status() == 404) {
			return List.of();
		}
		if (!answer.ok() || answer.body() == null) {
			return null;
		}
		JsonNode body = answer.body();
		List<String> keys = new ArrayList<>();
		if (body.path("value").isArray()) {
			body.path("value").forEach(item -> keys.add(memberKey(item)));
		}
		else {
			keys.add(memberKey(body));
		}
		return keys;
	}

	private static String memberKey(JsonNode item) {
		for (String field : KEYS) {
			if (item.has(field) && !item.path(field).isNull()) {
				return item.path(field).asString(item.path(field).toString());
			}
		}
		return item.toString();
	}

	/** The key a binding's path names last: {@code Elements('A')} binds {@code A}. */
	static String keyOf(String path) {
		if (path.endsWith("')")) {
			// Elements('A'), and a part of a key: Edges(ParentName='T',ComponentName='A')
			// The opening quote: back from the end, past doubled (escaped) ones
			int open = path.length() - 3;
			while (open >= 0) {
				if (path.charAt(open) == '\'') {
					if (open > 0 && path.charAt(open - 1) == '\'') {
						open -= 2;
						continue;
					}
					break;
				}
				open--;
			}
			return path.substring(open + 1, path.length() - 2).replace("''", "'");
		}
		int open = path.lastIndexOf('(');
		return open < 0 || !path.endsWith(")") ? path : path.substring(open + 1, path.length() - 1);
	}

	/**
	 * The case a nested entity of a type is made from: the one the cases' {@code nest}
	 * names for it, else the first by id for the type, else for a type derived from it.
	 */
	private @Nullable JsonNode caseFor(String type) {
		String chosen = cases.path("nest").path(type).asString("");
		for (JsonNode c : cases.path("cases")) {
			if (!chosen.isEmpty() && c.path("id").asString().equals(chosen)) {
				return c;
			}
		}
		JsonNode exact = null;
		JsonNode derived = null;
		for (JsonNode c : cases.path("cases")) {
			String t = c.path("type").asString();
			if (t.equals(type)) {
				exact = first(exact, c);
			}
			else if (metadata.typeHierarchy(qualified(t)).stream().anyMatch(l -> l.name().equals(type))) {
				derived = first(derived, c);
			}
		}
		return exact != null ? exact : derived;
	}

	private static JsonNode first(@Nullable JsonNode found, JsonNode c) {
		return found == null || c.path("id").asString().compareTo(found.path("id").asString()) < 0 ? c : found;
	}

	private static String simple(String qualifiedName) {
		return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
	}

	private JsonNode signedIn(String name, String set, String other) {
		ObjectNode result = NODES.objectNode();
		boolean with = client.signsIn(name, set);
		boolean without = client.signsIn(name, other);
		result.put("outcome", with && !without ? "applied" : "ignored");
		result.put("verifiedBy", "signIn");
		return result;
	}

	private JsonNode probeCollection(JsonNode c) {
		String collection = c.path("collection").asString().replace("{p}", prefix);
		String name = fresh(collection);
		JsonNode create = substitute(c.path("create"), name);
		JsonNode patch = substitute(c.path("patch"), name);
		String read = c.path("read").asString();
		String key = c.path("key").asString();
		ObjectNode result = NODES.objectNode();
		ProbeClient.Answer created = client.send("POST", collection, create);
		if (!created.ok()) {
			result.set("create", outcome(created));
			return result;
		}
		List<String> before = members(collection, name, read, key);
		ProbeClient.Answer answer = client.send("PATCH", path(collection, name), patch);
		List<String> after = members(collection, name, read, key);
		delete(collection, name);
		if (!answer.ok()) {
			return outcome(answer);
		}
		Set<String> added = new LinkedHashSet<>(after);
		added.removeAll(before);
		String outcome;
		if (after.equals(before)) {
			outcome = "ignored";
		}
		else if (added.isEmpty()) {
			outcome = new TreeSet<>(after).equals(new TreeSet<>(before)) ? "reorders" : "mixed";
		}
		else if (Collections.disjoint(after, before)) {
			outcome = "replaces";
		}
		else if (after.containsAll(before)) {
			outcome = "appends";
		}
		else {
			outcome = "mixed";
		}
		result.put("outcome", outcome);
		return result;
	}

	/**
	 * The members of a collection, by a key that may be a path
	 * ({@code Parameters/0/Value}).
	 */
	private List<String> members(String collection, String name, String read, String key) {
		ProbeClient.Answer nav = client.get(path(collection, name) + "/" + read);
		JsonNode items;
		if (nav.ok() && nav.body() != null) {
			items = nav.body().path("value");
		}
		else {
			ProbeClient.Answer selected = client.get(path(collection, name) + "?$select=" + read);
			items = selected.body() == null ? NODES.arrayNode() : selected.body().path(read);
		}
		List<String> keys = new ArrayList<>();
		for (JsonNode item : items) {
			JsonNode k = item;
			for (String part : List.of(key.split("/", -1))) {
				k = part.matches("\\d+") ? k.path(Integer.parseInt(part)) : k.path(part);
			}
			keys.add(k.asString(k.toString()));
		}
		return keys;
	}

	/**
	 * A value to send: the case's, else the fixture's if it isn't the default, else one
	 * made up.
	 */
	private @Nullable JsonNode valueFor(String type, String property, JsonNode values, @Nullable JsonNode fixture,
			@Nullable JsonNode defaultValue) {
		if (values.has(property)) {
			return substitute(values.get(property), "");
		}
		if (fixture != null && !same(fixture, defaultValue)) {
			return fixture;
		}
		JsonNode made = madeUp(type, property, defaultValue);
		// Undeclared and never read back: a string, to see what TM1 does with a write
		if (made == null && "unread".equals(undeclaredOf(type).get(property))) {
			return NODES.stringNode("Probe");
		}
		return made;
	}

	private @Nullable JsonNode madeUp(String type, String property, @Nullable JsonNode current) {
		Optional<CsdlProperty> declared = declaredProperty(type, property);
		if (declared.isPresent()) {
			if (declared.get().type().collection()) {
				return null;
			}
			String edm = declared.get().type().name();
			Optional<CsdlEnumType> enumType = metadata.enumType(metadata.qualify(edm));
			if (enumType.isPresent()) {
				String now = current == null ? "" : current.asString("");
				return enumType.get()
					.members()
					.stream()
					.map(CsdlEnumType.Member::name)
					.filter(m -> !m.equals(now))
					.findFirst()
					.map(m -> (JsonNode) NODES.stringNode(m))
					.orElse(null);
			}
			return switch (edm) {
				case "Edm.Boolean" -> NODES.booleanNode(current == null || !current.asBoolean());
				case "Edm.Int16", "Edm.Int32", "Edm.Int64", "Edm.Byte" ->
					NODES.numberNode((current == null || !current.isNumber() ? 0 : current.asLong()) + 1);
				case "Edm.Double", "Edm.Decimal", "Edm.Single" ->
					NODES.numberNode((current == null || !current.isNumber() ? 0 : current.asDouble()) + 1);
				case "Edm.String" ->
					NODES.stringNode(current == null || current.isNull() ? "Probe" : current.asString() + "X");
				case "Edm.Duration" -> NODES.stringNode("P2DT00H00M00S");
				case "Edm.DateTimeOffset" -> NODES.stringNode("2027-02-03T04:05:00Z");
				// A type of TM1's own, such as PrimitiveType: by the value held
				default -> fromCurrent(current);
			};
		}
		return fromCurrent(current);
	}

	/** A value made from the one held: flipped, one more, or with an X added. */
	private static @Nullable JsonNode fromCurrent(@Nullable JsonNode current) {
		if (current == null || current.isNull()) {
			return null;
		}
		if (current.isBoolean()) {
			return NODES.booleanNode(!current.asBoolean());
		}
		if (current.isNumber()) {
			return NODES.numberNode(current.asDouble() + 1);
		}
		return current.isString() ? NODES.stringNode(current.asString() + "X") : null;
	}

	private Optional<CsdlProperty> declaredProperty(String type, String property) {
		for (CsdlStructuredType t : metadata.typeHierarchy(qualified(type))) {
			for (CsdlProperty p : t.properties()) {
				if (p.name().equals(property)) {
					return Optional.of(p);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * The properties to probe: what the read returned, every structural property the type
	 * declares, and every undeclared one the probe found, but no navigations.
	 */
	private List<String> properties(String type, JsonNode read) {
		TreeSet<String> names = new TreeSet<>(read.propertyNames());
		for (CsdlStructuredType level : metadata.typeHierarchy(qualified(type))) {
			level.properties().forEach(p -> names.add(p.name()));
		}
		undeclaredOf(type).forEach((name, kind) -> {
			if (!kind.equals("navigation")) {
				names.add(name);
			}
		});
		return new ArrayList<>(names);
	}

	/**
	 * The undeclared properties the probe found for a type, and the kind each reads as.
	 */
	private Map<String, String> undeclaredOf(String type) {
		Map<String, String> found = undeclared.get(type);
		return found == null ? Map.of() : found;
	}

	/** A {@code $select} of every structural property, declared or not. */
	private String select(String type) {
		Set<String> names = new TreeSet<>();
		for (CsdlStructuredType t : metadata.typeHierarchy(qualified(type))) {
			t.properties().forEach(p -> names.add(p.name()));
		}
		Map<String, String> found = undeclared.get(type);
		if (found != null) {
			found.forEach((n, kind) -> {
				if (!kind.equals("navigation")) {
					names.add(n);
				}
			});
		}
		return "$select=" + String.join(",", names);
	}

	private String qualified(String type) {
		return metadata.structuredTypes()
			.filter(t -> t.name().equals(type))
			.findFirst()
			.flatMap(metadata::qualifiedName)
			.orElse(type);
	}

	static JsonNode classify(@Nullable JsonNode got, JsonNode sent, @Nullable JsonNode defaultValue) {
		ObjectNode result = NODES.objectNode();
		result.put("outcome", contains(got, sent) ? "applied" : same(got, defaultValue) ? "ignored" : "transformed");
		return result;
	}

	static ObjectNode outcome(ProbeClient.Answer answer) {
		ObjectNode result = NODES.objectNode();
		if (answer.ok()) {
			result.put("outcome", "accepted");
			return result;
		}
		result.put("outcome", "rejected");
		result.put("status", answer.status());
		JsonNode code = answer.body() == null ? null : answer.body().path("error").get("code");
		if (code != null && !code.isNull()) {
			result.put("code", code.asString());
		}
		return result;
	}

	/**
	 * Whether {@code got} holds what was sent: numbers by value, objects by their fields
	 * sent.
	 */
	static boolean contains(@Nullable JsonNode got, @Nullable JsonNode want) {
		if (want == null || want.isNull()) {
			return got == null || got.isNull();
		}
		if (got == null) {
			return false;
		}
		if (want.isNumber() && got.isNumber()) {
			return Double.compare(want.asDouble(), got.asDouble()) == 0;
		}
		if (want.isObject()) {
			if (!got.isObject()) {
				return false;
			}
			for (String field : want.propertyNames()) {
				if (!field.contains("@") && !contains(got.get(field), want.get(field))) {
					return false;
				}
			}
			return true;
		}
		if (want.isArray()) {
			if (!got.isArray() || got.size() != want.size()) {
				return false;
			}
			for (int i = 0; i < want.size(); i++) {
				if (!contains(got.get(i), want.get(i))) {
					return false;
				}
			}
			return true;
		}
		return want.equals(got);
	}

	static boolean same(@Nullable JsonNode a, @Nullable JsonNode b) {
		return contains(a, b) && contains(b, a);
	}

	private ObjectNode readBack(String at, String select) {
		ProbeClient.Answer answer = client.get(at + "?" + select);
		return answer.ok() && answer.body() != null ? clean(answer.body()) : NODES.objectNode();
	}

	private String fresh(String collection) {
		String name = prefix + "W" + String.format(Locale.ROOT, "%03d", sequence++);
		delete(collection, name);
		return name;
	}

	private void delete(String collection, String name) {
		client.send("DELETE", path(collection, name), null);
	}

	static String path(String collection, String name) {
		return collection + "('" + name.replace("'", "''") + "')";
	}

	private ObjectNode withName(JsonNode base, String name) {
		ObjectNode body = merge(base, NODES.objectNode());
		body.put("Name", name);
		return body;
	}

	private static ObjectNode merge(JsonNode base, JsonNode more) {
		ObjectNode body = NODES.objectNode();
		base.properties().forEach(e -> body.set(e.getKey(), e.getValue().deepCopy()));
		more.properties().forEach(e -> body.set(e.getKey(), e.getValue().deepCopy()));
		return body;
	}

	static ObjectNode clean(JsonNode read) {
		ObjectNode body = NODES.objectNode();
		read.properties().forEach(e -> {
			if (!e.getKey().contains("@")) {
				body.set(e.getKey(), e.getValue());
			}
		});
		return body;
	}

	private static List<String> sortedFields(JsonNode node) {
		return new ArrayList<>(new TreeSet<>(node.propertyNames()));
	}

	/**
	 * Replaces {@code {p}} with the prefix and {@code {name}} with a name, in every
	 * string.
	 */
	private JsonNode substitute(JsonNode node, String name) {
		if (node.isString()) {
			return NODES.stringNode(node.asString().replace("{p}", prefix).replace("{name}", name));
		}
		if (node.isArray()) {
			ArrayNode out = NODES.arrayNode();
			node.forEach(n -> out.add(substitute(n, name)));
			return out;
		}
		if (node.isObject()) {
			ObjectNode out = NODES.objectNode();
			node.properties()
				.forEach(e -> out.set(e.getKey().replace("{p}", prefix).replace("{name}", name),
						substitute(e.getValue(), name)));
			return out;
		}
		return node.deepCopy();
	}

}
