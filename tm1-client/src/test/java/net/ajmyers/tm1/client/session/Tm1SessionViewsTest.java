package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

/** Sandbox views and the session context label. */
class Tm1SessionViewsTest {

	private static final Tm1Request<JsonNode> CUBE = Tm1Request.get(ODataPath.of("Cubes").key("Sales"),
			ODataQuery.none().select("Name"), JsonNode.class);

	private final FakeTm1 tm1 = new FakeTm1()
		.on("GET Cubes('Sales')?$select=Name", r -> Mono.just(FakeTm1.json(200, "{}")))
		.on("GET Cubes('Sales')?$select=Name&!sandbox=What-if%201", r -> Mono.just(FakeTm1.json(200, "{}")))
		.on("GET Cubes('Sales')?$select=Name&!sandbox=Mine", r -> Mono.just(FakeTm1.json(200, "{}")));

	@Test
	void addsTheSandboxToEveryRequestOfTheViewAndSharesTheLogin() {
		Tm1Session base = tm1.session();
		Tm1Session whatIf = base.withSandbox("What-if 1");

		StepVerifier.create(whatIf.execute(CUBE).then(base.execute(CUBE)).then(whatIf.withoutSandbox().execute(CUBE)))
			.expectNextCount(1)
			.verifyComplete();

		assertThat(tm1.requests("GET Cubes('Sales')?$select=Name&!sandbox=What-if%201")).hasSize(1);
		assertThat(tm1.requests("GET Cubes('Sales')?$select=Name")).hasSize(2);
		assertThat(tm1.requests(FakeTm1.LOGIN)).as("views share one login").hasSize(1);
		assertThat(whatIf.sandbox()).hasValue("What-if 1");
		assertThat(base.sandbox()).isEmpty();
		assertThat(whatIf).hasToString("Tm1Session[" + FakeTm1.ROOT + ", sandbox=What-if 1]");
	}

	@Test
	void keepsASandboxTheRequestSetsItself() {
		Tm1Session whatIf = tm1.session().withSandbox("What-if 1");
		StepVerifier.create(whatIf.execute(CUBE.withQuery(CUBE.query().option("!sandbox", "Mine"))))
			.expectNextCount(1)
			.verifyComplete();
		assertThat(tm1.requests("GET Cubes('Sales')?$select=Name&!sandbox=Mine")).hasSize(1);
	}

	@Test
	void closingAViewClosesTheSharedSession() {
		Tm1Session base = tm1.session();
		Tm1Session whatIf = base.withSandbox("x");
		StepVerifier.create(base.connect().then(whatIf.close())).verifyComplete();
		assertThat(base.isClosed()).isTrue();
		assertThat(tm1.requests(FakeTm1.CLOSE)).hasSize(1);
	}

	@Test
	void sendsTheSessionContextWithTheLoginOnly() {
		Tm1Session session = tm1.session(Tm1Session.builder().sessionContext("planning-app"));
		StepVerifier.create(session.execute(CUBE)).expectNextCount(1).verifyComplete();

		assertThat(tm1.requests(FakeTm1.LOGIN).get(0).headers().getFirst("TM1-SessionContext"))
			.isEqualTo("planning-app");
		assertThat(tm1.requests("GET Cubes('Sales')?$select=Name").get(0).headers().getFirst("TM1-SessionContext"))
			.isNull();
	}

	@Test
	void refusesBlankSandboxesAndOverlongContexts() {
		assertThatIllegalArgumentException().isThrownBy(() -> tm1.session().withSandbox(" "));
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Session.builder().sessionContext("x".repeat(251)));
	}

}
