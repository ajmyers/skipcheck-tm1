package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.error.Tm1ApiException;
import net.ajmyers.tm1.client.error.Tm1AuthenticationException;
import net.ajmyers.tm1.client.error.Tm1BadRequestException;
import net.ajmyers.tm1.client.error.Tm1ErrorKind;
import net.ajmyers.tm1.client.error.Tm1ForbiddenException;
import net.ajmyers.tm1.client.error.Tm1TransportException;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/** Impersonation, and how the session treats a login TM1 refuses. */
class Tm1SessionImpersonationTest {

	private static final Tm1Request<JsonNode> SERVER = Tm1Request.get(ODataPath.of("Server"), ODataQuery.none(),
			JsonNode.class);

	private final FakeTm1 tm1 = new FakeTm1().on("GET Server", r -> Mono.just(FakeTm1.json(200, "{}")));

	private final AtomicLong now = new AtomicLong();

	@Test
	void impersonatesAtLoginOnly() {
		Tm1Session session = tm1.session(Tm1Session.builder().impersonate("alice"));
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();

		assertThat(tm1.requests(FakeTm1.LOGIN).get(0).headers().getFirst("TM1-Impersonate")).isEqualTo("alice");
		assertThat(tm1.requests("GET Server").get(0).headers().getFirst("TM1-Impersonate")).isNull();
	}

	@Test
	void refusesNamesTm1WouldMisread() {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Session.builder().impersonate(""))
			.withMessageContaining("no impersonation");
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Session.builder().impersonate("  "));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Session.builder().impersonate("zoë"))
			.withMessageContaining("printable ASCII");
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Session.builder().impersonate("a\nb"));
		Tm1Session.builder().impersonate("carol smith");
	}

	@Test
	void reportsAnUnknownUser() {
		tm1.on(FakeTm1.LOGIN, r -> Mono.just(text(400,
				"Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n")));
		StepVerifier.create(tm1.session(Tm1Session.builder().impersonate("nobody")).execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1BadRequestException.class,
					a -> assertThat(a.kind()).isEqualTo(Tm1ErrorKind.UNKNOWN_USER)))
			.verify();
	}

	@Test
	void reportsARefusedImpersonationWhateverTheWording() {
		tm1.on(FakeTm1.LOGIN, r -> Mono.just(text(403, "Error: Forbidden\r\nSome other version's words.\r\n")));
		StepVerifier.create(tm1.session(Tm1Session.builder().impersonate("Admin")).execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1ForbiddenException.class,
					a -> assertThat(a.kind()).isEqualTo(Tm1ErrorKind.IMPERSONATION_REFUSED)))
			.verify();
	}

	@Test
	void namesBothCausesOfA401WhenImpersonating() {
		tm1.on(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.status(401)));
		StepVerifier.create(tm1.session(Tm1Session.builder().impersonate("bob")).execute(SERVER))
			.expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(Tm1AuthenticationException.class, a -> {
				assertThat(a.kind()).isEqualTo(Tm1ErrorKind.CREDENTIAL_REJECTED);
				assertThat(a.getMessage()).contains("bob").contains("disabled");
			}))
			.verify();
	}

	@Test
	void remembersARefusedLoginAndFailsFastUntilTheBackOffEnds() {
		tm1.onEach(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.status(401)), r -> Mono.just(FakeTm1.status(401)),
				r -> Mono.just(FakeTm1.json(200, "{\"ID\":9}", "TM1SessionId=ok; Path=/api/")));
		Tm1Session session = session(Tm1Timeouts.builder().refusedLogin(Duration.ofSeconds(30)).build());

		StepVerifier.create(session.execute(SERVER)).expectError(Tm1AuthenticationException.class).verify();
		now.addAndGet(Duration.ofSeconds(29).toNanos());
		StepVerifier.create(session.execute(SERVER)).expectError(Tm1AuthenticationException.class).verify();
		assertThat(tm1.requests(FakeTm1.LOGIN)).as("within the back-off: no new login").hasSize(1);

		now.addAndGet(Duration.ofSeconds(2).toNanos());
		StepVerifier.create(session.execute(SERVER)).expectError(Tm1AuthenticationException.class).verify();
		assertThat(tm1.requests(FakeTm1.LOGIN)).as("after the back-off: tried again").hasSize(2);

		now.addAndGet(Duration.ofSeconds(31).toNanos());
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
	}

	@Test
	void retriesAtOnceAfterAFailureThatIsntARefusal() {
		tm1.onEach(FakeTm1.LOGIN, r -> Mono.error(new IOException("Connection reset")),
				r -> Mono.just(FakeTm1.json(200, "{\"ID\":9}", "TM1SessionId=ok; Path=/api/")));
		Tm1Session session = session(Tm1Timeouts.DEFAULTS);
		StepVerifier.create(session.execute(SERVER)).expectError(Tm1TransportException.class).verify();
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
	}

	@Test
	void triesAgainAtOnceWhenTheBackOffIsZero() {
		tm1.onEach(FakeTm1.LOGIN, r -> Mono.just(FakeTm1.status(401)),
				r -> Mono.just(FakeTm1.json(200, "{\"ID\":9}", "TM1SessionId=ok; Path=/api/")));
		Tm1Session session = session(Tm1Timeouts.builder().refusedLogin(Duration.ZERO).build());
		StepVerifier.create(session.execute(SERVER)).expectError(Tm1ApiException.class).verify();
		StepVerifier.create(session.execute(SERVER)).expectNextCount(1).verifyComplete();
	}

	private Tm1Session session(Tm1Timeouts timeouts) {
		return tm1.session(Tm1Session.builder().timeouts(timeouts).nanoTime(now::get));
	}

	private static ClientResponse text(int status, String body) {
		return ClientResponse.create(HttpStatus.valueOf(status))
			.header(HttpHeaders.CONTENT_TYPE, "text/plain")
			.body(body)
			.build();
	}

}
