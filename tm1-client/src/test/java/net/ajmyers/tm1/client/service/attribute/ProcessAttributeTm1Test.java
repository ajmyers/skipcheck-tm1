package net.ajmyers.tm1.client.service.attribute;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.AttributeDefinition;
import net.ajmyers.tm1.client.model.AttributeDefinitionPatch;
import net.ajmyers.tm1.client.model.AttributeType;
import net.ajmyers.tm1.client.model.AttributesPatch;
import net.ajmyers.tm1.client.model.ChoreExecutionMode;
import net.ajmyers.tm1.client.model.ChorePatch;
import net.ajmyers.tm1.client.model.ChoreTaskPatch;
import net.ajmyers.tm1.client.model.LocalizedAttributesPatch;
import net.ajmyers.tm1.client.model.ProcessPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/** Attributes and translations of processes and chores. */
@RequiresTm1
class ProcessAttributeTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	private static String process;

	private static String chore;

	@BeforeAll
	static void createProcessAndChore(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		String id = UUID.randomUUID().toString().substring(0, 8);
		process = "tm1-client-" + id;
		chore = "tm1-client-chore-" + id;
		tm1.processes().create(ProcessPatch.builder().name(process).prologProcedure("nX = 1;\n").build()).block(WAIT);
		tm1.chores()
			.create(ChorePatch.builder()
				.name(chore)
				.startTime(OffsetDateTime.parse("2026-01-01T02:00:00Z"))
				.dstSensitive(false)
				.active(false)
				.executionMode(ChoreExecutionMode.SINGLE_COMMIT)
				.frequency(Duration.ofDays(1))
				.tasks(List.of(ChoreTaskPatch.builder().bindProcess(ODataPath.of("Processes").key(process)).build()))
				.build())
			.block(WAIT);
	}

	@AfterAll
	static void deleteProcessAndChore() {
		tm1.chores().delete(chore).block(WAIT);
		tm1.processes().delete(process).block(WAIT);
		tm1.close().block(WAIT);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "process", "chore" })
	void definesAndTranslatesAttributes(String kind) {
		AttributeSet set = kind.equals("process") ? AttributeSet.processes() : AttributeSet.chores();
		AttributeOwner owner = kind.equals("process") ? AttributeOwner.process(process) : AttributeOwner.chore(chore);
		String label = "Label " + UUID.randomUUID().toString().substring(0, 4);
		tm1.attributes()
			.create(set, AttributeDefinitionPatch.builder().name(label).type(AttributeType.STRING).build())
			.block(WAIT);
		try {
			assertThat(tm1.attributes()
				.list(set, ODataQuery.none())
				.map(AttributeDefinition::name)
				.collectList()
				.block(WAIT)).contains(label);
			tm1.localizedAttributes()
				.create(owner,
						LocalizedAttributesPatch.builder()
							.localeID("fr")
							.attributes(AttributesPatch.builder().property(label, "Charger").build())
							.build())
				.block(WAIT);
			assertThat(tm1.localizedAttributes()
				.get(owner, "fr", ODataQuery.none())
				.block(WAIT)
				.attributes()
				.dynamicProperties()
				.get(label)
				.asString()).isEqualTo("Charger");
		}
		finally {
			tm1.attributes().delete(set, label).block(WAIT);
		}
	}

}
