package net.ajmyers.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.error.Tm1ServerException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ChangeSetService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. The 1:1 calls are recorded to
 * {@code spec/conformance/calls/ChangeSetServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay; {@code within}, which takes the work as a function, is
 * tested here and in each client by hand.
 */
class ChangeSetServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final Duration WAIT = Duration.ofSeconds(5);

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void beginsEndsAndUndoes() {
		tm1().json("{\"@odata.context\":\"$metadata#Edm.String\",\"value\":\"7\"}");
		String id = conformance.call("changeSets", "begin");
		assertThat(id).isEqualTo("7");
		assertThat(tm1().last().key()).isEqualTo("POST BeginChangeSet");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().json("{\"@odata.context\":\"$metadata#Edm.Int64\",\"value\":3}");
		Long changes = conformance.call("changeSets", "end", "7");
		assertThat(changes).isEqualTo(3L);
		assertThat(tm1().last().key()).isEqualTo("POST EndChangeSet");
		assertThat(tm1().last().text()).isEqualTo("{\"ChangeSetID\":\"7\"}");

		tm1().status(204);
		conformance.call("changeSets", "undo", "7");
		assertThat(tm1().last().key()).isEqualTo("POST UndoChangeSet");
		assertThat(tm1().last().text()).isEqualTo("{\"ChangeSetID\":\"7\"}");
	}

	@Test
	void anUnknownChangeSetIsNotFound() {
		tm1().error(404, "278", "'x' can not be found");
		Throwable error = conformance.fail("changeSets", "undo", "x's");
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
		assertThat(tm1().last().text()).isEqualTo("{\"ChangeSetID\":\"x's\"}");

		tm1().error(404, "278", "'x' can not be found");
		conformance.fail("changeSets", "end", "x");
	}

	@Test
	void refusesAMissingId() {
		Throwable error = conformance.fail("changeSets", "end", (Object) null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	// within takes the work as a function: plain tests, hand-ported to every client

	private static Map<String, String> cell() {
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Region", "North");
		return cell;
	}

	@Test
	void worksWithinAChangeSetAndEndsIt() {
		tm1().json("{\"value\":\"7\"}");
		tm1().status(204);
		tm1().json("{\"value\":1}");
		List<String> out = conformance.client()
			.changeSets()
			.within(session -> Tm1Client.of(session).cells().update("Sales", cell(), 1).thenReturn("done"))
			.collectList()
			.block(WAIT);
		assertThat(out).containsExactly("done");
		List<ConformanceTm1.Recorded> requests = tm1().requests();
		assertThat(requests).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("POST BeginChangeSet", "POST Cubes('Sales')/tm1.Update?!ChangeSet=7", "POST EndChangeSet");
		assertThat(requests.get(2).text()).isEqualTo("{\"ChangeSetID\":\"7\"}");
	}

	@Test
	void endsTheChangeSetWhenTheWorkFails() {
		tm1().json("{\"value\":\"7\"}");
		tm1().json("{\"value\":0}");
		Flux<Object> failing = conformance.client()
			.changeSets()
			.within(session -> Mono.error(new IllegalStateException("work")));
		assertThatThrownBy(() -> failing.blockLast(WAIT)).hasMessageContaining("work");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("POST BeginChangeSet", "POST EndChangeSet");
	}

	@Test
	void endsTheChangeSetWhenTheWorkIsCancelled() {
		tm1().json("{\"value\":\"7\"}");
		tm1().json("{\"value\":0}");
		List<Integer> first = conformance.client()
			.changeSets()
			.within(session -> Flux.range(1, 10))
			.take(2)
			.collectList()
			.block(WAIT);
		assertThat(first).containsExactly(1, 2);
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("POST BeginChangeSet", "POST EndChangeSet");
	}

	@Test
	void doesNoWorkIfTheChangeSetCantBegin() {
		tm1().error(500, "1", "down");
		Flux<String> work = conformance.client().changeSets().within(session -> Mono.just("never"));
		assertThatThrownBy(() -> work.blockLast(WAIT))
			.isInstanceOf(Tm1ServerException.class);
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key).containsExactly("POST BeginChangeSet");
	}

}
