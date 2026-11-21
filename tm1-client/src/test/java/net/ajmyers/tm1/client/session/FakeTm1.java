package net.ajmyers.tm1.client.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * A stand-in for TM1 behind WebClient, for unit tests of the session: answers requests by
 * {@code METHOD path?query} with canned responses, and records every request.
 *
 * <p>Unless told otherwise, a login ({@code GET ActiveSession?$select=ID}) succeeds with cookie
 * {@code c1} and session ID 1, the next with {@code c2} and 2, and so on, and {@code tm1.Close}
 * answers 204.
 */
final class FakeTm1 implements ExchangeFunction {

	static final String ROOT = "http://tm1.test/api/v1/";
	static final String LOGIN = "GET ActiveSession?$select=ID";
	static final String CLOSE = "POST ActiveSession/tm1.Close";

	private final Map<String, List<Function<ClientRequest, Mono<ClientResponse>>>> handlers = new ConcurrentHashMap<>();
	private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
	private int logins;

	FakeTm1() {
		on(LOGIN, request -> {
			int n;
			synchronized (this) {
				n = ++logins;
			}
			return Mono.just(json(200, "{\"ID\":" + n + "}", "TM1SessionId=c" + n + "; Path=/api/; HttpOnly"));
		});
		on(CLOSE, request -> Mono.just(ClientResponse.create(HttpStatus.NO_CONTENT).build()));
	}

	/** Answers {@code key} with {@code handler} from now on. */
	FakeTm1 on(String key, Function<ClientRequest, Mono<ClientResponse>> handler) {
		handlers.put(key, new ArrayList<>(List.of(handler)));
		return this;
	}

	/** Answers {@code key} with each handler once, in order, then keeps using the last one. */
	@SafeVarargs
	final FakeTm1 onEach(String key, Function<ClientRequest, Mono<ClientResponse>>... answers) {
		List<Function<ClientRequest, Mono<ClientResponse>>> list = new ArrayList<>();
		for (Function<ClientRequest, Mono<ClientResponse>> answer : answers) {
			list.add(answer);
		}
		handlers.put(key, list);
		return this;
	}

	Tm1Session session() {
		return session(Tm1Session.builder());
	}

	Tm1Session session(Tm1Session.Builder builder) {
		return builder.baseUrl(ROOT).basicAuth("admin", "apple").webClient(WebClient.builder().exchangeFunction(this)).build();
	}

	@Override
	public Mono<ClientResponse> exchange(ClientRequest request) {
		requests.add(request);
		String key = key(request);
		List<Function<ClientRequest, Mono<ClientResponse>>> answers = handlers.get(key);
		if (answers == null) {
			return Mono.just(json(404, "{\"error\":{\"code\":\"278\",\"message\":\"No fake for " + key + "\"}}"));
		}
		Function<ClientRequest, Mono<ClientResponse>> answer;
		synchronized (answers) {
			answer = answers.size() > 1 ? answers.remove(0) : answers.get(0);
		}
		return Mono.defer(() -> answer.apply(request));
	}

	List<ClientRequest> requests() {
		return requests;
	}

	List<ClientRequest> requests(String key) {
		return requests.stream().filter(r -> key(r).equals(key)).toList();
	}

	static String key(ClientRequest request) {
		String uri = request.url().toString();
		return request.method().name() + " " + (uri.startsWith(ROOT) ? uri.substring(ROOT.length()) : uri);
	}

	/** The request's Cookie header, or null. */
	static String cookie(ClientRequest request) {
		List<String> cookies = request.cookies().get(Connection.COOKIE);
		return cookies == null || cookies.isEmpty() ? null : cookies.get(0);
	}

	static String authorization(ClientRequest request) {
		return request.headers().getFirst(HttpHeaders.AUTHORIZATION);
	}

	static ClientResponse json(int status, String body, String... setCookies) {
		ClientResponse.Builder builder = ClientResponse.create(HttpStatus.valueOf(status)).header(HttpHeaders.CONTENT_TYPE, "application/json");
		for (String cookie : setCookies) {
			builder.header(HttpHeaders.SET_COOKIE, cookie);
		}
		return builder.body(body).build();
	}

	static ClientResponse status(int status, String... setCookies) {
		ClientResponse.Builder builder = ClientResponse.create(HttpStatus.valueOf(status));
		for (String cookie : setCookies) {
			builder.header(HttpHeaders.SET_COOKIE, cookie);
		}
		return builder.build();
	}

	static ClientResponse text(String body) {
		return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "text/plain").body(body).build();
	}
}
