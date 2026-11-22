package net.ajmyers.tm1.testcontainers;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Where a {@link Tm1ServerContainer} gets TM1 Server from.
 *
 * <p>
 * The {@code tm1-server} image contains no IBM software, so every container needs one of
 * these. They mirror the image's own sources (see the image's README).
 *
 * <p>
 * {@link #discover()} finds one from the environment, so tests don't hard-code paths.
 * Each setting is read first as a system property and then as an environment variable,
 * under the same name. The names match the {@code tm1-server} compose settings, so a
 * {@code .env} that works for compose also works for tests. The first source found wins,
 * in this order:
 *
 * <ol>
 * <li>{@code TM1_PRIVATE_IMAGE}: an image with TM1 built in ({@link PrivateImage})
 * <li>{@code TM1_HOME_DIR}: an installed {@code tm1_64} folder ({@link InstallDir})
 * <li>{@code TM1_INSTALL_VOLUME}: a Docker volume holding an install
 * ({@link InstallVolume})
 * <li>{@code TM1_BUNDLE_DIR}, optionally with {@code TM1_BUNDLE}: a folder holding IBM's
 * bundle, and the file name to use from it ({@link Bundle})
 * <li>{@code TM1_BUNDLE_URL}, optionally with {@code TM1_BUNDLE_SHA256}: a download link
 * for the bundle ({@link BundleUrl})
 * </ol>
 *
 * <p>
 * Most tests let {@link #discover()} find the source, so each developer and CI system can
 * supply TM1 their own way:
 *
 * {@snippet class = examples.Tm1SourceExamples region = discover}
 *
 * <p>
 * A test that needs a particular TM1 chooses it in code:
 *
 * {@snippet class = examples.Tm1SourceExamples region = explicit}
 *
 * <p>
 * A test setup with its own configuration reads the same settings from it with
 * {@link #discover(Function)}:
 *
 * {@snippet class = examples.Tm1SourceExamples region = own-settings}
 */
public sealed interface Tm1Source {

	/** Settings read by {@link #discover()}, in the order they're tried. */
	String PRIVATE_IMAGE = "TM1_PRIVATE_IMAGE";

	/** An installed {@code tm1_64} folder, for {@link InstallDir}. */
	String HOME_DIR = "TM1_HOME_DIR";

	/** A Docker volume holding an install, for {@link InstallVolume}. */
	String INSTALL_VOLUME = "TM1_INSTALL_VOLUME";

	/** A folder holding IBM's bundle, for {@link Bundle}. */
	String BUNDLE_DIR = "TM1_BUNDLE_DIR";

	/** The bundle's file name within {@link #BUNDLE_DIR}; the newest one if unset. */
	String BUNDLE = "TM1_BUNDLE";

	/** A download link for IBM's bundle, for {@link BundleUrl}. */
	String BUNDLE_URL = "TM1_BUNDLE_URL";

	/** The SHA-256 to check the {@link #BUNDLE_URL} download against; optional. */
	String BUNDLE_SHA256 = "TM1_BUNDLE_SHA256";

	/**
	 * A short description for logs and messages. Never includes a URL's query string.
	 * @return the description
	 */
	String describe();

	/**
	 * An image built on {@code tm1-server} with TM1 inside, from a <b>private</b>
	 * registry. It contains IBM software, so it must never be pushed anywhere public.
	 *
	 * @param image the image's full name, including its registry and tag
	 */
	record PrivateImage(String image) implements Tm1Source {
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
	 * An installed {@code tm1_64} folder, the one containing {@code bin64/tm1s.exe}.
	 * Mounted read-only.
	 *
	 * @param dir the {@code tm1_64} folder; made absolute
	 */
	record InstallDir(Path dir) implements Tm1Source {
		/** Makes the folder absolute and checks it holds {@code bin64/tm1s.exe}. */
		public InstallDir {
			Objects.requireNonNull(dir, "dir");
			dir = dir.toAbsolutePath().normalize();
			if (!Files.isRegularFile(dir.resolve("bin64/tm1s.exe"))) {
				throw new IllegalArgumentException("Not a TM1 install: " + dir + " has no bin64/tm1s.exe. Point "
						+ HOME_DIR + " at the tm1_64 folder of a Linux TM1 Server install.");
			}
		}

		@Override
		public String describe() {
			return "install folder " + dir;
		}
	}

	/**
	 * A named Docker volume holding a TM1 install, such as the one the {@code tm1-server}
	 * compose setup creates ({@code tm1_tm1-install}). If the volume is empty, the
	 * container fails to start, since there's nothing to install from.
	 *
	 * @param name the volume's name
	 */
	record InstallVolume(String name) implements Tm1Source {
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
	 * A folder holding IBM's {@code tm1server-*-linuxi38664h_bundle.tar.gz}, mounted
	 * read-only. The image installs TM1 from it on first start into a shared cache
	 * volume, so later starts are fast.
	 *
	 * @param dir the folder holding the bundle
	 * @param file the bundle's file name within {@code dir}, or {@code null} for the
	 * newest one
	 */
	record Bundle(Path dir, @Nullable String file) implements Tm1Source {
		/**
		 * Makes the folder absolute, treats a blank file name as {@code null}, and checks
		 * both exist.
		 */
		public Bundle {
			Objects.requireNonNull(dir, "dir");
			dir = dir.toAbsolutePath().normalize();
			if (!Files.isDirectory(dir)) {
				throw new IllegalArgumentException(BUNDLE_DIR + " is not a folder: " + dir
						+ ". Point it at the folder holding the bundle; name the file itself with " + BUNDLE + ".");
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
			return "bundle " + (file != null ? dir.resolve(file) : dir + " (newest)");
		}
	}

	/**
	 * A download link for IBM's bundle, such as a pre-signed S3, R2 or GCS URL. Links can
	 * carry credentials in the query string (pre-signed links) or the user-info part
	 * ({@code https://user:token@host/...}, common with artifact repositories), so
	 * neither is ever logged.
	 *
	 * @param url the link to download the bundle from
	 * @param sha256 the bundle's SHA-256, checked before installing; {@code null} to skip
	 */
	record BundleUrl(URI url, @Nullable String sha256) implements Tm1Source {
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
			return "bundle URL " + url.getScheme() + "://" + url.getHost()
					+ (url.getPort() == -1 ? "" : ":" + url.getPort()) + url.getRawPath();
		}
	}

	/**
	 * Finds a source from system properties and environment variables. See the class
	 * docs.
	 * @return the source, or empty if none is configured
	 * @throws IllegalArgumentException if a setting is present but invalid
	 */
	static Optional<Tm1Source> discover() {
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
	static Optional<Tm1Source> discover(Function<String, @Nullable String> lookup) {
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
		return Optional.empty();
	}

	private static void requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

}
