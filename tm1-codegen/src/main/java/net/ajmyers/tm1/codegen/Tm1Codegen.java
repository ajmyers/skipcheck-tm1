package net.ajmyers.tm1.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import net.ajmyers.tm1.csdl.CsdlDocument;
import net.ajmyers.tm1.csdl.CsdlParser;

/**
 * Command line entry point: {@code Tm1Codegen <snapshot.xml> <output directory>}. Run by
 * the {@code generateModel} task in {@code tm1-client}.
 */
public final class Tm1Codegen {

	private Tm1Codegen() {
	}

	/**
	 * Generates the model.
	 * @param args the snapshot file, then the output directory (emptied first)
	 * @throws IOException if reading or writing fails
	 */
	public static void main(String[] args) throws IOException {
		if (args.length != 2) {
			throw new IllegalArgumentException("Usage: Tm1Codegen <snapshot.xml> <output directory>");
		}
		CsdlDocument metadata;
		try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
			metadata = CsdlParser.parse(in);
		}
		new ModelGenerator(metadata).writeTo(Path.of(args[1]));
	}

}
