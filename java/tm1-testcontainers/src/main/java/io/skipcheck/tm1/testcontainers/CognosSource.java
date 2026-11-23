// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Where a {@link CognosContainer} gets IBM Cognos Analytics from.
 *
 * <p>
 * The {@code cognos-server} image contains no IBM software, so every container needs one
 * of these. Cognos writes into its install while it runs, so unlike TM1 an install can't
 * be mounted read-only and shared: a {@link PrivateImage} is the fastest source, and safe
 * for containers in parallel, because Docker gives each container its own copy-on-write
 * copy. The zip sources install Cognos into every new container (about a minute, and 10
 * GB).
 *
 * <p>
 * {@link #discover()} finds one from the environment. Each setting is read first as a
 * system property and then as an environment variable, under the same name. The names
 * match the {@code cognos-server} image's settings. The first source found wins, in this
 * order:
 *
 * <ol>
 * <li>{@code COGNOS_PRIVATE_IMAGE}: an image with Cognos built in ({@link PrivateImage})
 * <li>{@code COGNOS_INSTALL_VOLUME}: a Docker volume holding an install
 * ({@link InstallVolume})
 * <li>{@code COGNOS_ZIP_DIR}, optionally with {@code COGNOS_ZIP}: a folder holding IBM's
 * repository zip, and the file name to use from it ({@link Zip})
 * <li>{@code COGNOS_ZIP_URL}, optionally with {@code COGNOS_ZIP_SHA256}: a download link
 * for the zip ({@link ZipUrl})
 * <li>{@link Tm1Source#BUNDLE_DIR TM1_BUNDLE_DIR}, if it also holds a Cognos zip (or
 * {@code COGNOS_ZIP} names one in it): the same downloads folder as TM1
 * </ol>
 */
public sealed interface CognosSource {

	/** An image with Cognos built in, for {@link PrivateImage}. */
	String PRIVATE_IMAGE = "COGNOS_PRIVATE_IMAGE";

	/** A Docker volume holding an install, for {@link InstallVolume}. */
	String INSTALL_VOLUME = "COGNOS_INSTALL_VOLUME";

	/** A folder holding IBM's Cognos repository zip, for {@link Zip}. */
	String ZIP_DIR = "COGNOS_ZIP_DIR";

	/** The zip's file name within the zip folder; the newest one if unset. */
	String ZIP = "COGNOS_ZIP";

	/** A download link for IBM's Cognos repository zip, for {@link ZipUrl}. */
	String ZIP_URL = "COGNOS_ZIP_URL";

	/** The SHA-256 to check the {@link #ZIP_URL} download against; optional. */
	String ZIP_SHA256 = "COGNOS_ZIP_SHA256";

	/**
	 * A short description for logs and messages. Never includes a URL's query string.
	 * @return the description
	 */
	String describe();

	/**
	 * An image built on {@code cognos-server} with Cognos inside (see the image's
	 * {@code examples/private-image}), from a <b>private</b> registry or only on your
	 * machine. It contains IBM software, so it must never be pushed anywhere public.
	 *
	 * @param image the image's full name, including its registry and tag
	 */
	record PrivateImage(String image) implements CognosSource {
		/** Checks the image name isn't blank. */
		public PrivateImage {
			requireText(image, "image");
		}

		@Override
		public String describe() {
			return "private image " + image;
		}
	}

	/**
	 * A named Docker volume holding a Cognos install made by the image's {@code install}
	 * command. Cognos writes into it, so only one container may use it at a time.
	 *
	 * @param name the volume's name
	 */
	record InstallVolume(String name) implements CognosSource {
		/** Checks the volume name isn't blank. */
		public InstallVolume {
			requireText(name, "name");
		}

		@Override
		public String describe() {
			return "install volume " + name;
		}
	}

	/**
	 * A folder holding IBM's {@code casrv-*-linuxi38664h.zip}, mounted read-only. Each
	 * container installs Cognos from it when it starts.
	 *
	 * @param dir the folder holding the zip
	 * @param file the zip's file name within {@code dir}, or {@code null} for the newest
	 * one
	 */
	record Zip(Path dir, @Nullable String file) implements CognosSource {
		/**
		 * Makes the folder absolute, treats a blank file name as {@code null}, and checks
		 * both exist.
		 */
		public Zip {
			Objects.requireNonNull(dir, "dir");
			dir = dir.toAbsolutePath().normalize();
			if (!Files.isDirectory(dir)) {
				throw new IllegalArgumentException(ZIP_DIR + " is not a folder: " + dir
						+ ". Point it at the folder holding the zip; name the file itself with " + ZIP + ".");
			}
			if (file != null && file.isBlank()) {
				file = null;
			}
			if (file != null && !Files.isRegularFile(dir.resolve(file))) {
				throw new IllegalArgumentException(ZIP + " names " + file + ", which isn't in " + dir);
			}
		}

		@Override
		public String describe() {
			return "zip " + (file != null ? dir.resolve(file) : dir + " (newest)");
		}
	}

	/**
	 * A download link for IBM's Cognos repository zip, such as a pre-signed S3 URL.
	 * Neither the query string nor the user-info part is ever logged.
	 *
	 * @param url the link to download the zip from
	 * @param sha256 the zip's SHA-256, checked before installing; {@code null} to skip
	 */
	record ZipUrl(URI url, @Nullable String sha256) implements CognosSource {
		/** Checks the URL is set and treats a blank SHA-256 as {@code null}. */
		public ZipUrl {
			Objects.requireNonNull(url, "url");
			if (sha256 != null && sha256.isBlank()) {
				sha256 = null;
			}
		}

		@Override
		public String describe() {
			// Host and port only: the authority would include any user:password@
			return "zip URL " + url.getScheme() + "://" + url.getHost()
					+ (url.getPort() == -1 ? "" : ":" + url.getPort()) + url.getRawPath();
		}
	}

	/**
	 * Finds a source from system properties and environment variables. See the class
	 * docs.
	 * @return the source, or empty if none is configured
	 * @throws IllegalArgumentException if a setting is present but invalid
	 */
	static Optional<CognosSource> discover() {
		return discover(name -> {
			String value = System.getProperty(name);
			return value != null ? value : System.getenv(name);
		});
	}

	/**
	 * Finds a source using {@code lookup} to read each setting. Blank values count as
	 * unset.
	 * @param lookup reads a setting by name, returning {@code null} if it's unset
	 * @return the first source found, or empty if none is configured
	 * @throws IllegalArgumentException if a setting is present but invalid, such as a
	 * folder that doesn't exist. A wrong setting fails rather than silently skipping the
	 * tests.
	 */
	static Optional<CognosSource> discover(Function<String, @Nullable String> lookup) {
		Function<String, @Nullable String> get = name -> {
			String value = lookup.apply(name);
			return value == null || value.isBlank() ? null : value.strip();
		};
		if (get.apply(PRIVATE_IMAGE) != null) {
			return Optional.of(new PrivateImage(get.apply(PRIVATE_IMAGE)));
		}
		if (get.apply(INSTALL_VOLUME) != null) {
			return Optional.of(new InstallVolume(get.apply(INSTALL_VOLUME)));
		}
		if (get.apply(ZIP_DIR) != null) {
			return Optional.of(new Zip(Path.of(get.apply(ZIP_DIR)), get.apply(ZIP)));
		}
		if (get.apply(ZIP_URL) != null) {
			return Optional.of(new ZipUrl(URI.create(get.apply(ZIP_URL)), get.apply(ZIP_SHA256)));
		}
		// The TM1 downloads folder, when it holds Cognos too. Skipped quietly when it
		// doesn't: that folder is TM1's setting, and is valid without Cognos in it.
		String tm1Dir = get.apply(Tm1Source.BUNDLE_DIR);
		if (tm1Dir != null && (get.apply(ZIP) != null || hasZip(Path.of(tm1Dir)))) {
			return Optional.of(new Zip(Path.of(tm1Dir), get.apply(ZIP)));
		}
		return Optional.empty();
	}

	private static boolean hasZip(Path dir) {
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(file -> file.getFileName().toString())
				.anyMatch(name -> name.startsWith("casrv-") && name.endsWith("-linuxi38664h.zip"));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Couldn't list " + dir, e);
		}
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

}
