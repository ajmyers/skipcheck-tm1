// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * Command line entry point:
 *
 * <ul>
 * <li>{@code Tm1Codegen <snapshot.xml> <output directory>} generates the Java model (the
 * {@code generateModel} task in {@code tm1-client});</li>
 * <li>{@code Tm1Codegen spec <snapshot.xml> <spec.json>} exports the language-neutral API
 * spec other clients are generated from (the {@code exportSpec} task).</li>
 * </ul>
 */
public final class Tm1Codegen {

	private Tm1Codegen() {
	}

	/**
	 * Generates the model, or exports the spec.
	 * @param args the snapshot file, then the output directory (emptied first); or
	 * {@code spec}, the snapshot file and the spec file
	 * @throws IOException if reading or writing fails
	 */
	public static void main(String[] args) throws IOException {
		if (args.length == 3 && "spec".equals(args[0])) {
			Path snapshot = Path.of(args[1]);
			Model model = Model.of(snapshot);
			String spec = new ApiSpecExporter(model.metadata(), model.overlay(), snapshot.getFileName().toString(),
					model.undeclared(), model.writes(), model.probe())
				.export();
			Path out = Path.of(args[2]);
			if (out.getParent() != null) {
				Files.createDirectories(out.getParent());
			}
			Files.writeString(out, spec, StandardCharsets.UTF_8);
			return;
		}
		if (args.length != 2) {
			throw new IllegalArgumentException(
					"Usage: Tm1Codegen <snapshot.xml> <output directory> | Tm1Codegen spec <snapshot.xml> <spec.json>");
		}
		Model model = Model.of(Path.of(args[0]));
		new ModelGenerator(model.metadata(), model.overlay(), model.undeclared(), model.writes())
			.writeTo(Path.of(args[1]));
	}

	/**
	 * What the model is generated from: the snapshot, with the properties the probe file
	 * beside it ({@code tm1-probe-<schema>.json}) found undeclared, what it found writes
	 * do, and the bundled overlay.
	 */
	record Model(CsdlDocument metadata, Overlay overlay, Undeclared undeclared, Writes writes, @Nullable String probe) {

		static Model of(Path snapshot) throws IOException {
			CsdlDocument metadata = read(snapshot);
			Overlay overlay = Overlay.bundled();
			String version = metadata.schemaVersion().orElse("");
			Path dir = snapshot.toAbsolutePath().getParent();
			Path probe = dir == null ? null : dir.resolve("tm1-probe-" + version + ".json");
			if (probe == null || !Files.exists(probe)) {
				return new Model(metadata, overlay, Undeclared.NONE, Writes.NONE, null);
			}
			String json = Files.readString(probe, StandardCharsets.UTF_8);
			Undeclared undeclared = Undeclared.read(json, overlay, metadata);
			return new Model(undeclared.applyTo(metadata), undeclared.applyTo(overlay), undeclared, Writes.read(json),
					probe.getFileName().toString());
		}

	}

	private static CsdlDocument read(Path snapshot) throws IOException {
		try (InputStream in = Files.newInputStream(snapshot)) {
			return CsdlParser.parse(in);
		}
	}

}
