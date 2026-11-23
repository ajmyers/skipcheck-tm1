// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.MessageLogEntry;
import io.skipcheck.tm1.client.model.ProcessExecuteResult;
import io.skipcheck.tm1.client.model.ProcessPatch;
import io.skipcheck.tm1.client.model.TransactionLogEntry;
import io.skipcheck.tm1.client.model.UserPatch;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Mono;

/** LogService: the message, transaction, audit and error logs, and following them. */
@RequiresTm1
class LogServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1ServerContainer server;

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer container) {
		server = container;
		tm1 = client();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	private static Tm1Client client() {
		return Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

	@Test
	void readsTheMessageLog() {
		List<MessageLogEntry> first = tm1.logs().messageLogEntries(ODataQuery.none().top(20)).collectList().block(WAIT);
		assertThat(first).isNotEmpty();
		assertThat(first).as("an entry without a time reads as null rather than failing")
			.anyMatch(entry -> entry.timeStamp() == null);
		MessageLogEntry again = tm1.logs().messageLogEntries(ODataQuery.none().top(1)).blockFirst(WAIT);
		assertThat(again.message()).isEqualTo(first.get(0).message());
		assertThat(again.id()).as("an ID numbers the entry within its response").isNotEqualTo(first.get(0).id());

		List<MessageLogEntry> newest = tm1.logs().messageLog(true, ODataQuery.none().top(3)).collectList().block(WAIT);
		assertThat(newest).hasSize(3);
		assertThat(newest.get(0).timeStamp()).isAfterOrEqualTo(newest.get(2).timeStamp());
	}

	@Test
	void followsTheMessageLogWithoutRepeating() {
		Mono<Void> logins = Mono.delay(Duration.ofMillis(1500))
			.then(Mono.usingWhen(Mono.fromSupplier(LogServiceTm1Test::client),
					c -> c.cubes().list(ODataQuery.none()).then(), Tm1Client::close))
			.then(Mono.delay(Duration.ofMillis(1500)))
			.then(Mono.usingWhen(Mono.fromSupplier(LogServiceTm1Test::client),
					c -> c.cubes().list(ODataQuery.none()).then(), Tm1Client::close));
		List<MessageLogEntry> seen = tm1.logs()
			.tailMessageLog(Duration.ofMillis(300))
			.take(Duration.ofSeconds(6))
			.mergeWith(logins.then(Mono.empty()))
			.collectList()
			.block(WAIT);
		assertThat(seen).filteredOn(entry -> entry.message().contains("Login Success")).hasSizeGreaterThanOrEqualTo(2);
		Set<String> distinct = new HashSet<>();
		for (MessageLogEntry entry : seen) {
			distinct.add(entry.timeStamp() + " " + entry.threadID() + " " + entry.sessionID() + " " + entry.message());
		}
		assertThat(distinct).as("no entry twice, though each poll returns them all again").hasSameSizeAs(seen);
	}

	@Test
	void readsTheTransactionAndAuditLogs() {
		assertThat(tm1.logs().transactionLogEntries(ODataQuery.none().top(5)).collectList().block(WAIT)).isNotEmpty();
		assertThat(tm1.logs().transactionLog(true, true, ODataQuery.none().top(5)).collectList().block(WAIT))
			.allMatch(entry -> entry.statusMessage() == null);
		assertThat(
				tm1.logs().auditLogEntries(ODataQuery.none().expand("AuditDetails").top(5)).collectList().block(WAIT))
			.as("the audit log is off on the test server")
			.isEmpty();
		assertThat(tm1.logs()
			.tailTransactionLog(Duration.ofMillis(300))
			.take(Duration.ofSeconds(1))
			.collectList()
			.block(WAIT)).isNotNull();
		assertThat(
				tm1.logs().tailAuditLog(Duration.ofMillis(300)).take(Duration.ofSeconds(1)).collectList().block(WAIT))
			.isEmpty();
	}

	@Test
	void readsAPasswordsHashThatIsntUtf8AsReplacementCharacters() {
		String user = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.users().create(UserPatch.builder().name(user).password("pw").build()).block(WAIT);
		try {
			List<TransactionLogEntry> newest = tm1.logs()
				.transactionLog(true, true, ODataQuery.none().top(50))
				.collectList()
				.block(WAIT);
			assertThat(newest)
				.filteredOn(entry -> "}ClientProperties".equals(entry.cube()) && entry.tuple().contains("PASSWORD")
						&& entry.tuple().contains(user))
				.as("the password's hash, which TM1 sends as bytes that aren't UTF-8")
				.singleElement()
				.satisfies(entry -> assertThat(entry.newValue().asString()).contains("\uFFFD"));
		}
		finally {
			tm1.users().delete(user).block(WAIT);
		}
	}

	@Test
	void readsAProcessErrorLog() {
		String process = "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
		tm1.processes()
			.create(ProcessPatch.builder().name(process).prologProcedure("ProcessError;\n").build())
			.block(WAIT);
		try {
			ProcessExecuteResult result = tm1.processes()
				.executeWithReturn(process, Map.of(), ODataQuery.none().expand("ErrorLogFile"))
				.block(WAIT);
			String filename = result.errorLogFile().filename();
			assertThat(tm1.logs().errorLogFiles(ODataQuery.none()).collectList().block(WAIT))
				.anyMatch(f -> filename.equals(f.filename()));
			assertThat(tm1.logs().errorLogFile(filename, ODataQuery.none()).block(WAIT).filename()).isEqualTo(filename);
			assertThat(tm1.logs().errorLogContent(filename).block(WAIT)).as("without the byte order mark")
				.startsWith("Error:")
				.contains("ProcessError");
		}
		finally {
			tm1.processes().delete(process).block(WAIT);
		}
	}

}
