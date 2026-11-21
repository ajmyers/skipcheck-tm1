package net.ajmyers.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Where a {@link PawContainer} gets Planning Analytics Workspace from: IBM's PAW Local
 * zip, {@code ipa_workspace_local_<release>.<build>.zip}.
 *
 * <p>
 * IBM ships PAW Local as Docker images, compose files and scripts in that zip. Nothing
 * from it is in this library, so every {@link PawContainer} needs one.
 *
 * <p>
 * {@link #discover()} finds one from the environment. Each setting is read first as a
 * system property and then as an environment variable, under the same name, the names
 * {@code docker/paw/paw-local.sh} uses:
 *
 * <ol>
 * <li>{@code PAW_ZIP}: the zip itself
 * <li>{@code PAW_BUNDLE_DIR}: a folder holding it; the newest
 * {@code ipa_workspace_local_*.zip} there is used
 * </ol>
 *
 * @param zip the PAW Local zip; made absolute
 */
public record PawSource(Path zip) {

	/** The PAW Local zip. */
	public static final String ZIP = "PAW_ZIP";

	/** A folder holding the PAW Local zip; the newest one there is used. */
	public static final String BUNDLE_DIR = "PAW_BUNDLE_DIR";

	private static final String PREFIX = "ipa_workspace_local_";

	/** Makes the path absolute and checks it's a file. */
	public PawSource {
		Objects.requireNonNull(zip, "zip");
		zip = zip.toAbsolutePath().normalize();
		if (!Files.isRegularFile(zip)) {
			throw new IllegalArgumentException("Not a file: " + zip + ". Point " + ZIP
					+ " at IBM's ipa_workspace_local_<release>.<build>.zip, or " + BUNDLE_DIR + " at its folder.");
		}
	}

	/**
	 * A short description for logs and messages.
	 * @return the description
	 */
	public String describe() {
		return "PAW Local zip " + zip;
	}

	/**
	 * Finds a source from system properties and environment variables. See the class
	 * docs.
	 * @return the source, or empty if none is configured
	 * @throws IllegalArgumentException if a setting is present but invalid
	 */
	public static Optional<PawSource> discover() {
		return discover(PawSource::setting);
	}

	/**
	 * Finds a source using {@code lookup} to read each setting. Blank values count as
	 * unset.
	 * @param lookup reads a setting by name, returning {@code null} if it's unset
	 * @return the source, or empty if none is configured
	 * @throws IllegalArgumentException if a setting is present but invalid, such as a
	 * folder with no PAW Local zip in it. A wrong setting fails rather than silently
	 * skipping the tests.
	 */
	public static Optional<PawSource> discover(Function<String, @Nullable String> lookup) {
		String zip = text(lookup.apply(ZIP));
		if (zip != null) {
			return Optional.of(new PawSource(Path.of(zip)));
		}
		String dir = text(lookup.apply(BUNDLE_DIR));
		if (dir != null) {
			return Optional.of(new PawSource(newestZip(Path.of(dir))));
		}
		return Optional.empty();
	}

	/** A setting, read as a system property and then as an environment variable. */
	static @Nullable String setting(String name) {
		String value = System.getProperty(name);
		return value != null ? value : System.getenv(name);
	}

	private static Path newestZip(Path dir) {
		if (!Files.isDirectory(dir)) {
			throw new IllegalArgumentException(BUNDLE_DIR + " is not a folder: " + dir);
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(file -> {
				String name = file.getFileName().toString();
				return name.startsWith(PREFIX) && name.endsWith(".zip") && Files.isRegularFile(file);
			})
				.max(Comparator.comparing(PawSource::modified))
				.orElseThrow(() -> new IllegalArgumentException(
						BUNDLE_DIR + " has no " + PREFIX + "*.zip: " + dir.toAbsolutePath().normalize()));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't list " + dir, e);
		}
	}

	private static Long modified(Path file) {
		try {
			return Files.getLastModifiedTime(file).toMillis();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't read " + file, e);
		}
	}

	private static @Nullable String text(@Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
