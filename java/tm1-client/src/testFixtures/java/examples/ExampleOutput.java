// SPDX-License-Identifier: Apache-2.0

package examples;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an example printed, recorded for the docs: the walkthroughs show each step's
 * output under its code (api-site/README.md, "Walkthroughs, and what the examples
 * print"). A live test runs an example's steps through {@link #record}, and
 * {@link #close} writes {@code api-site/example-output/<project>/java/<Example>.json},
 * with each region's output and a hash of its code. The site's build fails when a region
 * that prints has no recording, when its code has changed since (the hash differs), or
 * when the languages printed different things.
 *
 * <p>
 * The Python, .NET and TypeScript live tests write the same file for their language, and
 * hash a region the same way: {@link #sourceHash}. A test fixture of tm1-client, so the
 * other modules' tests record their examples with it too (tm1-spring-boot-starter's).
 */
public final class ExampleOutput implements AutoCloseable {

	private static final Pattern START = Pattern.compile("@start\\s+region\\s*=\\s*\"?([\\w-]+)\"?");

	private static final Pattern END = Pattern.compile("@end\\b");

	private final String project;

	private final String example;

	private final String source;

	private final Map<String, Object> regions = new TreeMap<>();

	private boolean failed;

	/**
	 * @param project the project whose examples these are, such as {@code tm1-client}
	 * @param example the examples class, such as {@code ConnectWalkthrough}
	 */
	public ExampleOutput(String project, String example) {
		this.project = project;
		this.example = example;
		try {
			this.source = Files.readString(
					repo().resolve(Path.of("java", project, "src", "examples", "java", "examples", example + ".java")));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Runs a region's code, keeping what it prints to {@code System.out}, which still
	 * reaches the test log.
	 */
	public void record(String region, Runnable step) {
		String hash = sourceHash(source, region);
		PrintStream original = System.out;
		ByteArrayOutputStream printed = new ByteArrayOutputStream();
		System.setOut(new PrintStream(printed, true, UTF_8));
		boolean ran = false;
		try {
			step.run();
			ran = true;
		}
		finally {
			System.setOut(original);
			failed |= !ran;
		}
		String text = printed.toString(UTF_8);
		original.print(text);
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("source", hash);
		entry.put("output", lines(text));
		regions.put(region, entry);
	}

	/** What a region printed, as {@link #record} keeps it. */
	public List<String> output(String region) {
		@SuppressWarnings("unchecked")
		List<String> lines = (List<String>) ((Map<String, Object>) regions.get(region)).get("output");
		return lines;
	}

	/**
	 * Writes the recording, replacing the one before, unless a step failed: then the one
	 * before stays.
	 */
	@Override
	public void close() {
		if (failed) {
			return;
		}
		Map<String, Object> file = new LinkedHashMap<>();
		file.put("example", example);
		file.put("language", "java");
		file.put("regions", regions);
		Path path = repo().resolve(Path.of("api-site", "example-output", project, "java", example + ".json"));
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path,
					JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(file)
							+ "\n");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The hash of a region's code, as every language and the site's build work it out:
	 * the lines between its {@code @start region = <name>} and {@code @end}, each without
	 * the whitespace around it, blank ones left out, joined with newlines, as the SHA-256
	 * of their UTF-8, in hexadecimal. Indentation doesn't count, so reformatting a file
	 * only changes the hash where a line's own text changes.
	 */
	static String sourceHash(String source, String region) {
		List<String> lines = new ArrayList<>();
		boolean inside = false;
		boolean found = false;
		for (String line : source.split("\\R", -1)) {
			Matcher start = START.matcher(line);
			if (start.find()) {
				inside = start.group(1).equals(region);
				found |= inside;
			}
			else if (inside && END.matcher(line).find()) {
				inside = false;
			}
			else if (inside && !line.isBlank()) {
				lines.add(line.strip());
			}
		}
		if (!found) {
			throw new IllegalArgumentException("no region " + region);
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Printed text as lines, without their trailing whitespace and without the empty
	 * lines at the end.
	 */
	static List<String> lines(String text) {
		List<String> lines = new ArrayList<>();
		for (String line : text.split("\\R", -1)) {
			lines.add(line.stripTrailing());
		}
		while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private static Path repo() {
		String repo = System.getProperty("tm1.repo");
		if (repo == null) {
			throw new IllegalStateException("tm1.repo isn't set: run the tests with Gradle");
		}
		return Path.of(repo);
	}

}
