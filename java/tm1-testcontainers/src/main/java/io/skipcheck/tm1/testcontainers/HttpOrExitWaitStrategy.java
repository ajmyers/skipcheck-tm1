// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.IntPredicate;

import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

import com.github.dockerjava.api.command.InspectContainerResponse;

/**
 * Waits for an HTTP path to answer, as {@code Wait.forHttp} does, but stops waiting as
 * soon as the container has exited, and says why with the end of its log. Testcontainers'
 * own HTTP wait keeps trying until its timeout, so a TM1 that can't start (a missing
 * library, exit 127) cost the whole startup timeout, for every container, before anything
 * said so.
 */
final class HttpOrExitWaitStrategy extends AbstractWaitStrategy {

	private static final Duration POLL = Duration.ofSeconds(1);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	private static final int LOG_LINES = 30;

	// One for every wait: Java 17 has no HttpClient.close(), so a client per start would
	// leave its selector thread until it's collected. HTTP/1.1 and redirects followed, as
	// the HttpURLConnection behind Wait.forHttp has it
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(REQUEST_TIMEOUT)
		.build();

	private final String path;

	private final int port;

	private final IntPredicate ok;

	HttpOrExitWaitStrategy(String path, int port, IntPredicate ok, Duration timeout) {
		this.path = path;
		this.port = port;
		this.ok = ok;
		withStartupTimeout(timeout);
	}

	@Override
	protected void waitUntilReady() {
		// The monotonic clock: a clock change or a sleep doesn't move the deadline
		long deadline = System.nanoTime() + startupTimeout.toNanos();
		while (true) {
			InspectContainerResponse info = waitStrategyTarget.getCurrentContainerInfo();
			InspectContainerResponse.ContainerState state = info.getState();
			if (!Boolean.TRUE.equals(state.getRunning())) {
				throw new ContainerLaunchException(exited(state.getExitCodeLong()));
			}
			URI uri = URI
				.create("http://" + waitStrategyTarget.getHost() + ":" + waitStrategyTarget.getMappedPort(port) + path);
			try {
				HttpResponse<Void> response = HTTP.send(
						HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).GET().build(),
						HttpResponse.BodyHandlers.discarding());
				if (ok.test(response.statusCode())) {
					return;
				}
			}
			catch (IOException ex) {
				// Not listening yet
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new ContainerLaunchException("Interrupted while waiting for " + uri, ex);
			}
			if (System.nanoTime() - deadline > 0) {
				throw new ContainerLaunchException(
						"Timed out after " + startupTimeout + " waiting for " + uri + " to answer");
			}
			try {
				Thread.sleep(POLL.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new ContainerLaunchException("Interrupted while waiting for " + uri, ex);
			}
		}
	}

	private String exited(@Nullable Long code) {
		List<String> lines = waitStrategyTarget.getLogs().lines().toList();
		String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size()));
		return "The container exited while starting, with code " + code + ". The end of its log:\n" + tail;
	}

}
