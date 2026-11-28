// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlMerger;
import io.skipcheck.tm1.csdl.CsdlParser;

/**
 * Command line entry point:
 *
 * <ul>
 * <li>{@code Tm1Codegen <snapshot1.xml> [<snapshot2.xml> ...] <output directory>}
 * generates the Java model (the {@code generateModel} task in {@code tm1-client});</li>
 * <li>{@code Tm1Codegen spec <snapshot1.xml> [<snapshot2.xml> ...] <spec.json>} exports
 * the language-neutral API spec other clients are generated from (the {@code exportSpec}
 * task).</li>
 * </ul>
 *
 * <p>
 * When multiple snapshots are given (oldest-first), they are unioned by
 * {@link CsdlMerger#merge}, local {@code edmx:Reference} schemas beside the snapshots
 * (such as {@code ibm.tm1s.api.v1.xml} and {@code ibm.odata.mpu.v1.xml}) are resolved,
 * and every matching {@code tm1-probe-<schema>.json} file beside them is merged.
 */
public final class Tm1Codegen {

	private Tm1Codegen() {
	}

	/**
	 * Generates the model, or exports the spec.
	 * @param args one or more snapshot files followed by the output directory (emptied
	 * first); or {@code spec}, one or more snapshot files, and the spec file
	 * @throws IOException if reading or writing fails
	 */
	public static void main(String[] args) throws IOException {
		if (args.length >= 3 && "spec".equals(args[0])) {
			List<Path> snapshots = Arrays.stream(args, 1, args.length - 1).map(Path::of).toList();
			Model model = Model.of(snapshots);
			String spec = new ApiSpecExporter(model.metadata(), model.overlay(), model.snapshots(), model.undeclared(),
					model.writes(), model.offers(), model.probes())
				.export();
			Path out = Path.of(args[args.length - 1]);
			if (out.getParent() != null) {
				Files.createDirectories(out.getParent());
			}
			Files.writeString(out, spec, StandardCharsets.UTF_8);
			return;
		}
		if (args.length < 2) {
			throw new IllegalArgumentException(
					"Usage: Tm1Codegen <snapshot.xml>... <output directory> | Tm1Codegen spec <snapshot.xml>... <spec.json>");
		}
		List<Path> snapshots = Arrays.stream(args, 0, args.length - 1).map(Path::of).toList();
		Model model = Model.of(snapshots);
		new ModelGenerator(model.metadata(), model.overlay(), model.undeclared(), model.writes(), model.offers())
			.writeTo(Path.of(args[args.length - 1]));
	}

	/**
	 * What the model is generated from: the merged snapshots (with local
	 * {@code edmx:Reference} types resolved), the properties the probe files beside them
	 * ({@code tm1-probe-<schema>.json}) found undeclared, what they found writes do, and
	 * the bundled overlay.
	 */
	record Model(CsdlDocument metadata, Overlay overlay, Undeclared undeclared, Writes writes, Offers offers,
			List<String> snapshots, List<String> probes) {

		static Model of(List<Path> snapshots) throws IOException {
			if (snapshots.isEmpty()) {
				throw new IllegalArgumentException("At least one snapshot is required");
			}
			List<CsdlDocument> docs = new ArrayList<>();
			List<String> snapshotNames = new ArrayList<>();
			List<Undeclared.Probe> undeclaredProbes = new ArrayList<>();
			List<String> probeJsons = new ArrayList<>();
			List<String> probeNames = new ArrayList<>();
			for (Path snapshot : snapshots) {
				CsdlDocument doc = read(snapshot);
				docs.add(doc);
				snapshotNames.add(snapshot.getFileName().toString());
				String version = doc.schemaVersion().orElse("");
				Path dir = snapshot.toAbsolutePath().getParent();
				Path probe = dir == null || version.isEmpty() ? null : dir.resolve("tm1-probe-" + version + ".json");
				if (probe != null && Files.exists(probe)) {
					String json = Files.readString(probe, StandardCharsets.UTF_8);
					CsdlDocument resolvedDoc = CsdlMerger.resolveReferences(doc, uri -> resolveFile(dir, uri));
					undeclaredProbes.add(new Undeclared.Probe(json, resolvedDoc));
					probeJsons.add(json);
					probeNames.add(probe.getFileName().toString());
				}
			}
			Path refDir = snapshots.get(snapshots.size() - 1).toAbsolutePath().getParent();
			CsdlDocument merged = CsdlMerger.resolveReferences(CsdlMerger.merge(docs), uri -> resolveFile(refDir, uri));
			Overlay overlay = Overlay.bundled();
			if (probeJsons.isEmpty()) {
				return new Model(merged, overlay, Undeclared.NONE, Writes.NONE, Offers.NONE, snapshotNames, List.of());
			}
			Undeclared undeclared = Undeclared.read(undeclaredProbes, overlay, merged);
			CsdlDocument withUndeclared = undeclared.applyTo(merged);
			return new Model(withUndeclared, undeclared.applyTo(overlay), undeclared, Writes.read(probeJsons, overlay),
					Offers.of(probeJsons, withUndeclared, overlay), snapshotNames, probeNames);
		}

		private static Optional<CsdlDocument> resolveFile(@Nullable Path dir, String uri) {
			if (dir == null) {
				return Optional.empty();
			}
			Path target = dir.resolve(uri).normalize();
			if (!target.startsWith(dir) || !Files.isRegularFile(target)) {
				return Optional.empty();
			}
			try {
				return Optional.of(read(target));
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

	}

	private static CsdlDocument read(Path snapshot) throws IOException {
		try (InputStream in = Files.newInputStream(snapshot)) {
			return CsdlParser.parse(in);
		}
	}

}
