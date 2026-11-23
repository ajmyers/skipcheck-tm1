package examples;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * Examples for {@link Tm1Source}, quoted in its Javadoc.
 */
final class Tm1SourceExamples {

	Tm1ServerContainer discover() {
		// @start region = discover
		// From TM1_HOME_DIR, TM1_BUNDLE_DIR and the like; empty if none is set
		Tm1Source source = Tm1Source.discover()
			.orElseThrow(() -> new IllegalStateException("Set TM1_HOME_DIR or TM1_BUNDLE_DIR"));
		return new Tm1ServerContainer(source);
		// @end
	}

	List<Tm1Source> explicit() {
		// @start region = explicit
		// An installed tm1_64 folder, mounted read-only: nothing to install
		Tm1Source installed = new Tm1Source.InstallDir(Path.of("/opt/ibm/tm1_64"));
		// IBM's bundle, installed once into a shared cache volume; null picks the newest
		Tm1Source bundle = new Tm1Source.Bundle(Path.of("/downloads/ibm"), null);
		// A download link, such as a pre-signed S3 URL, and its SHA-256 or null
		Tm1Source link = new Tm1Source.BundleUrl(URI.create("https://downloads.example.com/tm1server.tar.gz"),
				"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
		// An image with TM1 inside, from a private registry: it contains IBM software
		Tm1Source image = new Tm1Source.PrivateImage("registry.example.com/tm1-server:11.8.03500.6");
		return List.of(installed, bundle, link, image);
		// @end
	}

	Tm1Source ownSettings(Properties testSettings) {
		// @start region = own-settings
		// The same settings, read from the test setup's own configuration
		return Tm1Source.discover(testSettings::getProperty).orElseThrow();
		// @end
	}

	private Tm1SourceExamples() {
	}

}
