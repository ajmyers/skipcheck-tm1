// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.conformance;

import java.util.Map;
import java.util.function.BiFunction;

import org.reactivestreams.Publisher;

/**
 * A handler every runner knows by name, so a call that takes one can be recorded: encoded
 * as {@code {"$handler": "cells"}}.
 *
 * <ul>
 * <li>{@code cells}: a cellset stream's handler that gives back its cells, in order:
 * {@code (cellset, cells) -> cells}.
 * </ul>
 */
public final class ConformanceHandler implements BiFunction<Object, Publisher<?>, Publisher<?>> {

	/** {@code (cellset, cells) -> cells}. */
	public static final ConformanceHandler CELLS = new ConformanceHandler("cells");

	private static final Map<String, ConformanceHandler> ALL = Map.of(CELLS.name, CELLS);

	private final String name;

	private ConformanceHandler(String name) {
		this.name = name;
	}

	public String name() {
		return name;
	}

	/** The handler of that name. */
	public static ConformanceHandler named(String name) {
		ConformanceHandler handler = ALL.get(name);
		if (handler == null) {
			throw new IllegalArgumentException("Unknown handler " + name);
		}
		return handler;
	}

	@Override
	public Publisher<?> apply(Object cellset, Publisher<?> cells) {
		return cells;
	}

	@Override
	public String toString() {
		return "$handler " + name;
	}

}
