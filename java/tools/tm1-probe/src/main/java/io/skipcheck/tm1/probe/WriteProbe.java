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
		for (String key : sortedFields(shape.base)) {
			if (key.startsWith("@")) {
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
		for (String property : sortedFields(read)) {
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
			JsonNode targets = c.path("targets").path(target).isString() ? c.path("targets") : cases.path("targets");
			String bound = targets.path(target).isString() ? text(targets.path(target).asString(), "") : null;
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
			JsonNode nestedCase = caseFor(target);
			if (nestedCase == null || !nestedCase.path("named").asBoolean(true)) {
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
	private record Sent(String property, JsonNode value, String key, @Nullable String cleanup) {
	}

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
		return result.put("outcome",
				after.equals(baseline) ? "ignored" : after.contains(sent.key()) ? "applied" : "transformed");
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
		else if (!after.contains(sent.key())) {
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
		if (item.path("Name").isString()) {
			return item.path("Name").asString();
		}
		return item.has("ID") ? item.path("ID").asString(item.path("ID").toString()) : item.toString();
	}

	/** The key a binding's path names last: {@code Elements('A')} binds {@code A}. */
	static String keyOf(String path) {
		int open = path.lastIndexOf("('");
		return open < 0 ? path : path.substring(open + 2, path.length() - 2).replace("''", "'");
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
