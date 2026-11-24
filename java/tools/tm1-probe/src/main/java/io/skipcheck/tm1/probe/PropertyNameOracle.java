// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Asks TM1 which names are properties of a type, through {@code $select}.
 *
 * <p>
 * TM1 (11.8) refuses a {@code $select} naming a property its type doesn't have (400, code
 * 278, {@code "X" is not a valid property of type "ibm.tm1.api.v1.Process"}), and accepts
 * one it has, declared or not. So each candidate goes in a {@code $select} of its own, a
 * thousand to a {@code $batch}. It's deterministic: the same server and candidates give
 * the same names, whatever order the batches run in.
 *
 * <p>
 * Where TM1 doesn't check, a type has no answer: it then accepts a name nobody would give
 * a property ({@value #NO_SUCH_PROPERTY}). TM1 (11.8) doesn't check types with subtypes
 * ({@code View}), nor {@code Annotation}, nor properties of complex values
 * ({@code DataSource/x}).
 */
public final class PropertyNameOracle {

	/** A name no type has, to tell whether TM1 checks names at a path. */
	public static final String NO_SUCH_PROPERTY = "SkipcheckProbeNoSuchProperty";

	private final ProbeClient client;

	private final int parallelism;

	/**
	 * An oracle.
	 * @param client the server's client
	 * @param parallelism how many batches to send at once
	 */
	public PropertyNameOracle(ProbeClient client, int parallelism) {
		if (parallelism < 1) {
			throw new IllegalArgumentException("parallelism must be at least 1");
		}
		this.client = client;
		this.parallelism = parallelism;
	}

	/**
	 * Whether TM1 checks the names in a {@code $select} at a path.
	 * @param path a path to an entity or a collection of the type, from the REST root
	 * @param known a property the type is known to have
	 * @return whether it accepts {@code known} and refuses {@link #NO_SUCH_PROPERTY}
	 */
	public boolean checks(String path, String known) {
		return client.get(select(path, known)).ok() && !client.get(select(path, NO_SUCH_PROPERTY)).ok();
	}

	/**
	 * The candidates TM1 accepts in a {@code $select} at a path. Check the path with
	 * {@link #checks} first: where TM1 doesn't check, it accepts everything.
	 * @param path a path to an entity or a collection of the type, from the REST root
	 * @param candidates the names to try
	 * @return the names accepted, sorted
	 */
	public NavigableSet<String> accepted(String path, Collection<String> candidates) {
		List<String> names = List.copyOf(new TreeSet<>(candidates));
		List<List<String>> chunks = new ArrayList<>();
		for (int i = 0; i < names.size(); i += ProbeClient.BATCH_LIMIT) {
			chunks.add(names.subList(i, Math.min(names.size(), i + ProbeClient.BATCH_LIMIT)));
		}
		NavigableSet<String> accepted = new TreeSet<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
			List<Future<List<String>>> futures = new ArrayList<>();
			for (List<String> chunk : chunks) {
				futures.add(pool.submit(() -> acceptedIn(path, chunk)));
			}
			for (Future<List<String>> future : futures) {
				accepted.addAll(future.get());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted probing " + path, e);
		}
		catch (ExecutionException e) {
			throw new IllegalStateException("Probing " + path + " failed", e.getCause());
		}
		return Collections.unmodifiableNavigableSet(accepted);
	}

	private List<String> acceptedIn(String path, List<String> chunk) {
		List<String> paths = chunk.stream().map(name -> select(path, name)).toList();
		List<Integer> statuses = client.batchGetStatuses(paths);
		List<String> accepted = new ArrayList<>();
		for (int i = 0; i < chunk.size(); i++) {
			if (statuses.get(i) >= 200 && statuses.get(i) < 300) {
				accepted.add(chunk.get(i));
			}
		}
		return accepted;
	}

	static String select(String path, String name) {
		return path + (path.contains("?") ? "&" : "?") + "$select=" + name;
	}

}
