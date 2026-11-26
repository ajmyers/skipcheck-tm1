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
 * Where a {@link Tm1EngineContainer} gets TM1 v12 from.
 *
 * <p>
 * The {@code tm1-engine} image contains no IBM software, so every container needs one of
 * these. They mirror the image's own sources (see the image's README), and
 * {@link Tm1Source}, which does the same for TM1 11.
 *
 * <p>
 * {@link #discover()} finds one from the environment, so tests don't hard-code paths.
 * Each setting is read first as a system property and then as an environment variable,
 * under the same name. The first source found wins, in this order:
 *
 * <ol>
 * <li>{@code TM1_ENGINE_PRIVATE_IMAGE}: an image with TM1 built in ({@link PrivateImage})
 * <li>{@code TM1_ENGINE_HOME_DIR}: an install the image made, as a folder
 * ({@link InstallDir})
 * <li>{@code TM1_ENGINE_INSTALL_VOLUME}: a Docker volume holding such an install
 * ({@link InstallVolume})
 * <li>{@code TM1_ENGINE_BUNDLE_DIR}, optionally with {@code TM1_ENGINE_BUNDLE}: a folder
 * holding IBM's package, and the file name to use from it ({@link Bundle})
 * <li>{@code TM1_ENGINE_BUNDLE_URL}, optionally with {@code TM1_ENGINE_BUNDLE_SHA256}: a
 * download link for the package ({@link BundleUrl})
 * <li>{@code TM1_BUNDLE_DIR}, TM1 11's downloads folder, when it holds a TM1 v12 package
 * too: one folder then serves both versions
 * </ol>
 */
public sealed interface Tm1EngineSource {

	/** Settings read by {@link #discover()}, in the order they're tried. */
	String PRIVATE_IMAGE = "TM1_ENGINE_PRIVATE_IMAGE";

	/** An install the image made, as a folder, for {@link InstallDir}. */
	String HOME_DIR = "TM1_ENGINE_HOME_DIR";

	/** A Docker volume holding an install, for {@link InstallVolume}. */
	String INSTALL_VOLUME = "TM1_ENGINE_INSTALL_VOLUME";

	/** A folder holding IBM's package, for {@link Bundle}. */
	String BUNDLE_DIR = "TM1_ENGINE_BUNDLE_DIR";

	/** The package's file name within {@link #BUNDLE_DIR}; the newest one if unset. */
	String BUNDLE = "TM1_ENGINE_BUNDLE";

	/** A download link for IBM's package, for {@link BundleUrl}. */
	String BUNDLE_URL = "TM1_ENGINE_BUNDLE_URL";

	/** The SHA-256 to check the {@link #BUNDLE_URL} download against; optional. */
	String BUNDLE_SHA256 = "TM1_ENGINE_BUNDLE_SHA256";

	/**
	 * A short description for logs and messages. Never includes a URL's query string.
	 * @return the description
	 */
	String describe();

	/**
	 * An image built on {@code tm1-engine} with TM1 inside, from a <b>private</b>
	 * registry. It contains IBM software, so it must never be pushed anywhere public.
	 *
	 * @param image the image's full name, including its registry and tag
	 */
	record PrivateImage(String image) implements Tm1EngineSource {
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
	 * A TM1 v12 install the {@code tm1-engine} image made ({@code install} as its
	 * command), as a folder: the one containing {@code bin/tm1sd.exe}. Mounted read-only.
	 * An install made by IBM's installer isn't one.
	 *
	 * @param dir the install folder; made absolute
	 */
	record InstallDir(Path dir) implements Tm1EngineSource {
		/** Makes the folder absolute and checks it holds {@code bin/tm1sd.exe}. */
		public InstallDir {
			Objects.requireNonNull(dir, "dir");
			dir = dir.toAbsolutePath().normalize();
			if (!Files.isRegularFile(dir.resolve("bin/tm1sd.exe"))) {
				throw new IllegalArgumentException("Not a TM1 v12 install: " + dir + " has no bin/tm1sd.exe. Point "
						+ HOME_DIR + " at an install the tm1-engine image made.");
			}
		}

		@Override
		public String describe() {
			return "install folder " + dir;
		}
	}

	/**
	 * A named Docker volume holding an install the {@code tm1-engine} image made. If the
	 * volume is empty, the container fails to start, since there's nothing to install
	 * from.
	 *
	 * @param name the volume's name
	 */
	record InstallVolume(String name) implements Tm1EngineSource {
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
	 * A folder holding IBM's {@code tm1-v<version>-standalone-linux.tgz}, mounted
	 * read-only. The image installs TM1 from it on first start into a shared cache
	 * volume, so later starts are fast. The folder can hold TM1 11, TM1 Web and Cognos
	 * downloads too: each image picks its own.
	 *
	 * @param dir the folder holding the package
	 * @param file the package's file name within {@code dir}, or {@code null} for the
	 * newest one
	 */
	record Bundle(Path dir, @Nullable String file) implements Tm1EngineSource {
		/**
		 * Makes the folder absolute, treats a blank file name as {@code null}, and checks
		 * both exist.
		 */
		public Bundle {
			Objects.requireNonNull(dir, "dir");
			dir = dir.toAbsolutePath().normalize();
			if (!Files.isDirectory(dir)) {
				throw new IllegalArgumentException(BUNDLE_DIR + " is not a folder: " + dir
						+ ". Point it at the folder holding the package; name the file itself with " + BUNDLE + ".");
			}
			if (file != null && file.isBlank()) {
				file = null;
			}
			if (file != null && !Files.isRegularFile(dir.resolve(file))) {
				throw new IllegalArgumentException(BUNDLE + " names " + file + ", which isn't in " + dir);
			}
		}

		@Override
		public String describe() {
			return "package " + (file != null ? dir.resolve(file) : dir + " (newest)");
		}
	}

	/**
	 * A download link for IBM's package, such as a pre-signed S3, R2 or GCS URL. Links
	 * can carry credentials in the query string or the user-info part, so neither is ever
	 * logged.
	 *
	 * @param url the link to download the package from
	 * @param sha256 the package's SHA-256, checked before installing; {@code null} to
	 * skip
	 */
	record BundleUrl(URI url, @Nullable String sha256) implements Tm1EngineSource {
		/** Checks the URL is set and treats a blank SHA-256 as {@code null}. */
		public BundleUrl {
			Objects.requireNonNull(url, "url");
			if (sha256 != null && sha256.isBlank()) {
				sha256 = null;
			}
		}

		@Override
		public String describe() {
			// Host and port only: the authority would include any user:password@
			return "package URL " + url.getScheme() + "://" + url.getHost()
					+ (url.getPort() == -1 ? "" : ":" + url.getPort()) + url.getRawPath();
		}
	}

	/**
	 * Finds a source from system properties and environment variables. See the class
	 * docs.
	 * @return the source, or empty if none is configured
	 * @throws IllegalArgumentException if a setting is present but invalid
	 */
	static Optional<Tm1EngineSource> discover() {
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
	static Optional<Tm1EngineSource> discover(Function<String, @Nullable String> lookup) {
		Function<String, @Nullable String> get = name -> {
			String value = lookup.apply(name);
			return value == null || value.isBlank() ? null : value.strip();
		};
		if (get.apply(PRIVATE_IMAGE) != null) {
			return Optional.of(new PrivateImage(get.apply(PRIVATE_IMAGE)));
		}
		if (get.apply(HOME_DIR) != null) {
			return Optional.of(new InstallDir(Path.of(get.apply(HOME_DIR))));
		}
		if (get.apply(INSTALL_VOLUME) != null) {
			return Optional.of(new InstallVolume(get.apply(INSTALL_VOLUME)));
		}
		if (get.apply(BUNDLE_DIR) != null) {
			return Optional.of(new Bundle(Path.of(get.apply(BUNDLE_DIR)), get.apply(BUNDLE)));
		}
		if (get.apply(BUNDLE_URL) != null) {
			return Optional.of(new BundleUrl(URI.create(get.apply(BUNDLE_URL)), get.apply(BUNDLE_SHA256)));
		}
		// The TM1 downloads folder, when it holds TM1 v12 too. Skipped quietly when it
		// doesn't: that folder is TM1 11's setting, and is valid without v12 in it.
		String tm1Dir = get.apply(Tm1Source.BUNDLE_DIR);
		if (tm1Dir != null && (get.apply(BUNDLE) != null || hasPackage(Path.of(tm1Dir)))) {
			return Optional.of(new Bundle(Path.of(tm1Dir), get.apply(BUNDLE)));
		}
		return Optional.empty();
	}

	private static boolean hasPackage(Path dir) {
		if (!Files.isDirectory(dir)) {
			return false;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(file -> file.getFileName().toString())
				.anyMatch(name -> name.startsWith("tm1-v") && name.endsWith("-standalone-linux.tgz"));
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
