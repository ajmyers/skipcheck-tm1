// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEnumType;
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

	private JsonNode probeCase(JsonNode c) {
		String type = c.path("type").asString();
		String collection = c.path("collection").asString().replace("{p}", prefix);
		JsonNode base = substitute(c.path("base"), "");
		JsonNode values = c.path("values");
		ObjectNode result = NODES.objectNode();
		result.put("type", type);

		String select = select(type);
		ProbeClient.Answer source = client.get(c.path("source").asString().replace("{p}", prefix) + "?" + select);
		if (!source.ok() || source.body() == null) {
			result.set("source", outcome(source));
			return result;
		}
		ObjectNode read = clean(source.body());

		// The read body as a create
		String name = fresh(collection);
		ObjectNode body = merge(base, read);
		body.put("Name", name);
		result.set("postRead", outcome(client.send("POST", collection, body)));
		delete(collection, name);

		// The least a create needs
		name = fresh(collection);
		ProbeClient.Answer minimal = client.send("POST", collection, withName(base, name));
		result.set("minimal", outcome(minimal));
		if (!minimal.ok()) {
			return result;
		}
		ObjectNode defaults = readBack(collection, name, select);
		delete(collection, name);
		ArrayNode required = result.putArray("required");
		for (String key : sortedFields(base)) {
			if (key.startsWith("@")) {
				continue;
			}
			ObjectNode without = withName(base, fresh(collection));
			without.remove(key);
			String n = without.path("Name").asString();
			if (!client.send("POST", collection, without).ok()) {
				required.add(key);
			}
			delete(collection, n);
		}

		ObjectNode post = result.putObject("post");
		ObjectNode patch = result.putObject("patch");
		ObjectNode patchNull = result.putObject("patchNull");
		for (String property : sortedFields(read)) {
			if (property.equals("Name")) {
				continue;
			}
			JsonNode value = valueFor(type, property, values, read.get(property), defaults.get(property));
			if (value == null) {
				post.putObject(property).put("outcome", "untested");
				patch.putObject(property).put("outcome", "untested");
			}
			else {
				post.set(property, tryPost(collection, base, select, property, value, defaults.get(property)));
				patch.set(property, tryPatch(collection, base, select, property, value));
			}
			String n = fresh(collection);
			client.send("POST", collection, withName(base, n));
			ObjectNode toNull = NODES.objectNode();
			toNull.putNull(property);
			patchNull.set(property, outcome(client.send("PATCH", path(collection, n), toNull)));
			delete(collection, n);
		}

		probeSecrets(c, collection, base, post, patch);

		// Renaming by PATCH
		String before = fresh(collection);
		String after = fresh(collection);
		client.send("POST", collection, withName(base, before));
		ObjectNode rename = NODES.objectNode();
		rename.put("Name", after);
		ProbeClient.Answer renamed = client.send("PATCH", path(collection, before), rename);
		boolean oldThere = client.get(path(collection, before)).ok();
		boolean newThere = client.get(path(collection, after)).ok();
		ObjectNode r = renamed.ok() ? NODES.objectNode() : outcome(renamed);
		if (renamed.ok()) {
			r.put("outcome", newThere && !oldThere ? "applied" : oldThere && !newThere ? "ignored" : "transformed");
		}
		result.set("rename", r);
		delete(collection, before);
		delete(collection, after);
		return result;
	}

	/**
	 * A create of the base plus one property, read back and compared with what a create
	 * of the base alone, under the same name, reads: so a value derived from the name (a
	 * {@code UniqueName}) reads as ignored, not changed. The create with the property
	 * comes first: TM1 can refuse a name just deleted when it comes back different (an
	 * element of another type, code 272). If the baseline is refused, the default is the
	 * baseline.
	 */
	private JsonNode tryPost(String collection, JsonNode base, String select, String property, JsonNode value,
			@Nullable JsonNode defaultValue) {
		String name = fresh(collection);
		ObjectNode body = withName(base, name);
		body.set(property, value);
		ProbeClient.Answer answer = client.send("POST", collection, body);
		JsonNode got = answer.ok() ? readBack(collection, name, select).get(property) : null;
		delete(collection, name);
		if (!answer.ok()) {
			return outcome(answer);
		}
		JsonNode baseline = defaultValue;
		if (client.send("POST", collection, withName(base, name)).ok()) {
			baseline = readBack(collection, name, select).get(property);
		}
		delete(collection, name);
		return logged(classify(got, value, baseline), collection, property, "POST", value, got);
	}

	/** A PATCH of one property on a fresh object, compared with what it read before. */
	private JsonNode tryPatch(String collection, JsonNode base, String select, String property, JsonNode value) {
		String name = fresh(collection);
		client.send("POST", collection, withName(base, name));
		JsonNode baseline = readBack(collection, name, select).get(property);
		ObjectNode body = NODES.objectNode();
		body.set(property, value);
		ProbeClient.Answer answer = client.send("PATCH", path(collection, name), body);
		JsonNode got = answer.ok() ? readBack(collection, name, select).get(property) : null;
		JsonNode result = answer.ok()
				? logged(classify(got, value, baseline), collection, property, "PATCH", value, got) : outcome(answer);
		delete(collection, name);
		return result;
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
	 * The case's secrets ({@code "secrets": {"Password": ["one", "two"]}}): properties
	 * TM1 never reads back, checked by their effect instead. A create with the first, and
	 * a PATCH to the second, is applied when the object then signs in with it and not
	 * with the other ({@link ProbeClient#signsIn}).
	 */
	private void probeSecrets(JsonNode c, String collection, JsonNode base, ObjectNode post, ObjectNode patch) {
		for (Map.Entry<String, JsonNode> secret : c.path("secrets").properties()) {
			String property = secret.getKey();
			String first = secret.getValue().path(0).asString();
			String second = secret.getValue().path(1).asString();
			String name = fresh(collection);
			ObjectNode body = withName(base, name);
			body.put(property, first);
			ProbeClient.Answer created = client.send("POST", collection, body);
			post.set(property, created.ok() ? signedIn(name, first, second) : outcome(created));
			delete(collection, name);

			name = fresh(collection);
			client.send("POST", collection, withName(base, name));
			ObjectNode change = NODES.objectNode();
			change.put(property, second);
			ProbeClient.Answer patched = client.send("PATCH", path(collection, name), change);
			patch.set(property, patched.ok() ? signedIn(name, second, first) : outcome(patched));
			delete(collection, name);
		}
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
		return madeUp(type, property, defaultValue);
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
				default -> null;
			};
		}
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

	private ObjectNode readBack(String collection, String name, String select) {
		ProbeClient.Answer answer = client.get(path(collection, name) + "?" + select);
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
			node.properties().forEach(e -> out.set(e.getKey(), substitute(e.getValue(), name)));
			return out;
		}
		return node.deepCopy();
	}

}
