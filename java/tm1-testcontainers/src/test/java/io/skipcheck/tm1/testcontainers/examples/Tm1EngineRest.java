// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import io.skipcheck.tm1.testcontainers.Tm1Database;
import io.skipcheck.tm1.testcontainers.Tm1Login;

/**
 * Plain HTTP against a TM1 v12 database, for the tests: no client library, so a client
 * bug can't break the tests of the containers.
 */
public final class Tm1EngineRest {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

	private Tm1EngineRest() {
	}

	/** GETs a path of the database's REST API, logged in. */
	public static HttpResponse<String> get(Tm1Database database, Tm1Login login, String path) {
		return send(login.authorize(HttpRequest.newBuilder(URI.create(database.getRestUrl() + path))).GET());
	}

	/** POSTs JSON to a path of the database's REST API, logged in. */
	public static HttpResponse<String> post(Tm1Database database, Tm1Login login, String path, String json) {
		return send(login.authorize(HttpRequest.newBuilder(URI.create(database.getRestUrl() + path)))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json)));
	}

	/** DELETEs a path of the database's REST API, logged in. */
	public static HttpResponse<String> delete(Tm1Database database, Tm1Login login, String path) {
		return send(login.authorize(HttpRequest.newBuilder(URI.create(database.getRestUrl() + path))).DELETE());
	}

	/** Sends a request of your own making. */
	public static HttpResponse<String> send(HttpRequest.Builder request) {
		try {
			return HTTP.send(request.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
