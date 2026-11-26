// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.sun.net.httpserver.HttpServer;

/**
 * The wait every container uses, without Docker: a target that has exited, one whose HTTP
 * answers, and one whose never does.
 */
class HttpOrExitWaitStrategyTest {

	@Test
	void failsAtOnceWithTheExitCodeAndTheEndOfTheLogWhenTheContainerHasExited() {
		String log = IntStream.rangeClosed(1, 40).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
		long started = System.nanoTime();
		assertThatThrownBy(() -> wait(Duration.ofMinutes(5)).waitUntilReady(target(false, 3, log, 1)))
			.isInstanceOf(ContainerLaunchException.class)
			.hasMessageStartingWith("The container exited while starting, with code 3. The end of its log:\nline 11\n")
			.hasMessageEndingWith("line 40")
			.hasMessageNotContaining("line 10\n");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
	}

	@Test
	void returnsOnceThePathAnswersAsWanted() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/ready", exchange -> {
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		try {
			wait(Duration.ofSeconds(30)).waitUntilReady(target(true, 0, "", server.getAddress().getPort()));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void timesOutWhenThePathNeverAnswers() {
		// A port nothing listens on: a closed one, found by binding and closing it
		assertThatThrownBy(() -> wait(Duration.ofSeconds(2)).waitUntilReady(target(true, 0, "", closedPort())))
			.isInstanceOf(ContainerLaunchException.class)
			.hasMessageStartingWith("Timed out after PT2S waiting for http://localhost:");
	}

	private static HttpOrExitWaitStrategy wait(Duration timeout) {
		return new HttpOrExitWaitStrategy("/ready", 8010, code -> code == 200, timeout);
	}

	private static WaitStrategyTarget target(boolean running, int exitCode, String log, int mappedPort) {
		InspectContainerResponse info = inspect(running, exitCode);
		return new WaitStrategyTarget() {
			@Override
			public List<Integer> getExposedPorts() {
				return List.of(8010);
			}

			@Override
			public InspectContainerResponse getContainerInfo() {
				return info;
			}

			@Override
			public InspectContainerResponse getCurrentContainerInfo() {
				return info;
			}

			@Override
			public String getHost() {
				return "localhost";
			}

			@Override
			public Integer getMappedPort(int originalPort) {
				return mappedPort;
			}

			@Override
			public String getLogs() {
				return log;
			}
		};
	}

	private static InspectContainerResponse inspect(boolean running, long exitCode) {
		return new InspectContainerResponse() {
			@Override
			public ContainerState getState() {
				return new ContainerState() {
					@Override
					public Boolean getRunning() {
						return running;
					}

					@Override
					public Long getExitCodeLong() {
						return exitCode;
					}
				};
			}
		};
	}

	private static int closedPort() {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
