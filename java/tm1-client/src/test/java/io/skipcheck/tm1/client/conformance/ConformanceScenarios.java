// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

import io.skipcheck.tm1.client.Tm1Client;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Runs a live conformance scenario ({@code spec/conformance/live/<name>.json}), checks
 * each step's expectation, and writes what the client sent to
 * {@code build/conformance-live/java/<name>.jsonl} for
 * {@code scripts/compare-live-traffic.py}, which compares it with the other clients'.
 */
public final class ConformanceScenarios {

	/**
	 * The session's own requests, which differ by when each client logs in and caches
	 * capabilities.
	 */
	private static final List<String> SESSION = List.of("ActiveSession", "$metadata", "Server?$select=ProductVersion");

	private ConformanceScenarios() {
	}

	/** {@code spec/conformance/live}. */
	public static Path directory() {
		return Conformance.directory().getParent().resolve("live");
	}

	public static List<Path> all() {
		try (Stream<Path> files = Files.list(directory())) {
			return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static JsonNode load(Path file) {
		return ConformanceCodec.json().readTree(file.toFile());
	}

	/** {@code build/conformance-live}, at the repository's root. */
	public static Path traffic() {
		return Conformance.directory().getParent().getParent().getParent().resolve("build").resolve("conformance-live");
	}

	/** A fresh name for each placeholder: letters, digits and _ only. */
	public static Map<String, String> names(JsonNode scenario) {
		String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		Map<String, String> names = new LinkedHashMap<>();
		scenario.get("names").forEach(n -> names.put(n.asString(), "tm1conf_" + run + "_" + n.asString()));
		return names;
	}

	static JsonNode substitute(JsonNode node, Map<String, String> names) {
		if (node.isString()) {
			String s = node.asString();
			for (Map.Entry<String, String> n : names.entrySet()) {
				s = s.replace("${" + n.getKey() + "}", n.getValue());
			}
			return StringNode.valueOf(s);
		}
		if (node.isArray()) {
			ArrayNode out = ConformanceCodec.json().createArrayNode();
			node.forEach(v -> out.add(substitute(v, names)));
			return out;
		}
		if (node.isObject()) {
			ObjectNode out = ConformanceCodec.json().createObjectNode();
			node.properties()
				.forEach(e -> out.set(substitute(StringNode.valueOf(e.getKey()), names).asString(),
						substitute(e.getValue(), names)));
			return out;
		}
		return node;
	}

	/** A filter that records what the client sends, labelled with the step. */
	public static final class Traffic implements ExchangeFilterFunction {

		private final String root;

		private final List<ObjectNode> exchanges = new ArrayList<>();

		private String step = "";

		public Traffic(String root) {
			this.root = root.endsWith("/") ? root : root + "/";
		}

		void step(String step) {
			this.step = step;
		}

		/** The step running: {@code steps[i]} or {@code finally[i]}. */
		String step() {
			return step;
		}

		public List<ObjectNode> exchanges() {
			return List.copyOf(exchanges);
		}

		@Override
		public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
			String url = request.url().toString();
			String target = url.startsWith(root) ? url.substring(root.length()) : url;
			if (SESSION.stream().anyMatch(target::startsWith)) {
				return next.exchange(request);
			}
			String label = step;
			return ConformanceTm1.body(request).flatMap(body -> next.exchange(request).doOnNext(response -> {
				Map<String, String> headers = new TreeMap<>();
				request.headers()
					.forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(", ", values)));
				ObjectNode req = ConformanceCodec.json().createObjectNode();
				req.put("method", request.method().name());
				req.put("target", target);
				ObjectNode h = req.putObject("headers");
				headers.forEach((name, value) -> {
					if (ConformanceTm1.HEADERS.contains(name)
							|| (name.startsWith("tm1-") && !name.equals("tm1-sessioncontext"))) {
						h.put(name, value);
					}
				});
				ConformanceTm1.putBody(req, headers.get("content-type"), body);
				ObjectNode exchange = ConformanceCodec.json().createObjectNode();
				exchange.put("step", label);
				exchange.set("request", req);
				exchange.put("status", response.statusCode().value());
				exchanges.add(exchange);
			}));
		}

		public Path write(String scenario, Map<String, String> names) {
			Path file = traffic().resolve("java").resolve(scenario + ".jsonl");
			ObjectNode head = ConformanceCodec.json().createObjectNode();
			head.put("scenario", scenario);
			head.put("language", "java");
			ObjectNode n = head.putObject("names");
			names.forEach(n::put);
			StringBuilder text = new StringBuilder(head.toString()).append('\n');
			exchanges.forEach(x -> text.append(x.toString()).append('\n'));
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, text, StandardCharsets.UTF_8);
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return file;
		}

	}

	/**
	 * Runs the scenario; the steps whose expectation failed. Its {@code finally} steps
	 * always run. What a step saves joins {@code names}, so the traffic written after it
	 * has TM1's made-up values as their placeholders too.
	 */
	public static List<String> run(Tm1Client client, Traffic traffic, JsonNode scenario, Map<String, String> names) {
		List<String> failures = new ArrayList<>();
		try {
			int i = 0;
			for (JsonNode step : scenario.get("steps")) {
				traffic.step("steps[" + i + "]");
				ObjectNode outcome = outcome(client, step, names);
				String why = check(substitute(step.get("expect"), names), outcome);
				if (why == null) {
					why = save(step, outcome, names);
				}
				if (why != null) {
					failures.add("step " + i + " " + step.get("service").asString() + "."
							+ step.get("method").asString() + ": " + why);
					break;
				}
				i++;
			}
		}
		finally {
			int i = 0;
			for (JsonNode step : scenario.path("finally")) {
				traffic.step("finally[" + i++ + "]");
				outcome(client, step, names);
			}
		}
		return failures;
	}

	/**
	 * Keeps what a step's {@code "save"} names from its result, {@code {"note": "ID"}}:
	 * the result's {@code ID} as the name {@code note}, for the steps after it, such as
	 * an ID TM1 made up. Why it can't, or null.
	 */
	static @Nullable String save(JsonNode step, JsonNode outcome, Map<String, String> names) {
		for (var s : step.path("save").properties()) {
			JsonNode result = outcome.path("result");
			JsonNode value = (result.has("$model") ? result.get("json") : result).path(s.getValue().asString());
			if (!value.isValueNode() || value.isNull()) {
				return "no " + s.getValue().asString() + " to save as " + s.getKey() + " in " + result;
			}
			names.put(s.getKey(), value.asString());
		}
		return null;
	}

	private static ObjectNode outcome(Tm1Client client, JsonNode step, Map<String, String> names) {
		ObjectNode out = ConformanceCodec.json().createObjectNode();
		Object service;
		try {
			service = Tm1Client.class.getMethod(step.get("service").asString()).invoke(client);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Tm1Client has no " + step.get("service"), e);
		}
		List<String> params = new ArrayList<>();
		step.get("params").forEach(p -> params.add(p.asString()));
		Method method = ConformanceDecoder.method(service.getClass(), step.get("method").asString(), params);
		Object[] args = ConformanceDecoder.args(method, substitute(step.get("args"), names));
		try {
			Object returned = method.invoke(service, args);
			Object value = returned instanceof Mono<?> mono ? mono.block()
					// A stream of bytes, such as a document's content, as its bytes
					: returned instanceof Flux<?> flux && Conformance.bytes(method) ? ConformanceCodec.bytes(flux)
							: returned instanceof Flux<?> flux ? flux.collectList().block() : returned;
			out.set("result", ConformanceCodec.result(value));
		}
		catch (InvocationTargetException e) {
			out.set("error", ConformanceCodec.error(e.getCause()));
		}
		catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		catch (RuntimeException e) {
			out.set("error", ConformanceCodec.error(Exceptions.unwrap(e)));
		}
		return out;
	}

	/**
	 * Equal JSON, numbers by their value ({@code 11} as an int or a long), at any depth.
	 */
	static boolean same(@Nullable JsonNode expected, @Nullable JsonNode got) {
		if (expected == null || got == null) {
			JsonNode given = expected != null ? expected : got;
			return given == null || given.isNull();
		}
		return expected.equals(
				(a, b) -> a.equals(b)
						|| (a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0) ? 0 : 1,
				got);
	}

	/**
	 * A model result's JSON with its type as {@code @odata.type}, for an expectation that
	 * names it: a result's JSON leaves out the type, which its {@code $model} gives.
	 */
	private static JsonNode typed(JsonNode result) {
		JsonNode json = result.get("json");
		if (json instanceof ObjectNode object && !object.has("@odata.type")) {
			ObjectNode typed = object.deepCopy();
			typed.put("@odata.type", "#ibm.tm1.api.v1." + result.get("$model").asString());
			return typed;
		}
		return json;
	}

	/** Why the outcome doesn't meet the expectation, or null if it does. */
	static @Nullable String check(JsonNode expect, JsonNode outcome) {
		if (expect.has("error")) {
			JsonNode got = outcome.get("error");
			for (var e : expect.get("error").properties()) {
				if (got == null || !same(e.getValue(), got.get(e.getKey()))) {
					return "expected the error " + expect.get("error") + ", got " + outcome;
				}
			}
			return null;
		}
		if (outcome.has("error")) {
			return "failed with " + outcome.get("error");
		}
		JsonNode result = outcome.get("result");
		JsonNode json = result.has("$model") ? typed(result) : result;
		if (expect.has("none") && !result.isNull()) {
			return "expected nothing, got " + result;
		}
		if (expect.has("count") && (!result.isArray() || result.size() != expect.get("count").asInt())) {
			return "expected " + expect.get("count") + " items, got " + result;
		}
		if (expect.has("fields")) {
			for (var f : expect.get("fields").properties()) {
				if (!same(f.getValue(), json.get(f.getKey()))) {
					return "expected the fields " + expect.get("fields") + ", got " + result;
				}
			}
		}
		if (expect.has("items")) {
			for (var f : expect.get("items").properties()) {
				ArrayNode got = ConformanceCodec.json().createArrayNode();
				result.forEach(item -> got.add((item.has("$model") ? item.get("json") : item).path(f.getKey())));
				if (!same(f.getValue(), got)) {
					return "expected the items' " + f.getKey() + " " + f.getValue() + ", got " + got;
				}
			}
		}
		return null;
	}

}
