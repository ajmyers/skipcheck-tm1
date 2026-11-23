package io.skipcheck.tm1.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
			String spec = new ApiSpecExporter(read(snapshot), Overlay.bundled(), snapshot.getFileName().toString())
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
		new ModelGenerator(read(Path.of(args[0]))).writeTo(Path.of(args[1]));
	}

	private static CsdlDocument read(Path snapshot) throws IOException {
		try (InputStream in = Files.newInputStream(snapshot)) {
			return CsdlParser.parse(in);
		}
	}

}
