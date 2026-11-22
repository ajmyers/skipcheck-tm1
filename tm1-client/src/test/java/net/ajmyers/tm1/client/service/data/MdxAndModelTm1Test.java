package net.ajmyers.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.error.Tm1BadRequestException;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.model.Dimension;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.testcontainers.RequiresTm1;
import net.ajmyers.tm1.testcontainers.Tm1ServerContainer;

/**
 * MDX statements, and the model and control cube and dimension functions
 * (MemberSetServiceTm1Test has the member sets).
 */
@RequiresTm1
class MdxAndModelTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void runsAnMdxStatementButNotCreateSet() {
		tm1.cellsets().executeStatement("SELECT {} ON 0 FROM [}ClientGroups]").block(WAIT);
		assertThatThrownBy(() -> tm1.cellsets()
			.executeStatement("CREATE SET [}Clients].[Probe] AS {TM1SUBSETALL([}Clients].[}Clients])}")
			.block(WAIT)).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void separatesModelAndControlObjects() {
		List<String> model = tm1.cubes()
			.modelCubes(ODataQuery.none().select(Cube.NAME))
			.map(Cube::name)
			.collectList()
			.block(WAIT);
		List<String> control = tm1.cubes()
			.controlCubes(ODataQuery.none().select(Cube.NAME))
			.map(Cube::name)
			.collectList()
			.block(WAIT);
		assertThat(model).noneMatch(n -> n.startsWith("}"));
		assertThat(control).isNotEmpty().allMatch(n -> n.startsWith("}")).contains("}ClientGroups");

		List<String> modelDims = tm1.dimensions()
			.modelDimensions(ODataQuery.none().select(Dimension.NAME))
			.map(Dimension::name)
			.collectList()
			.block(WAIT);
		List<String> controlDims = tm1.dimensions()
			.controlDimensions(ODataQuery.none().select(Dimension.NAME))
			.map(Dimension::name)
			.collectList()
			.block(WAIT);
		assertThat(modelDims).noneMatch(n -> n.startsWith("}"));
		assertThat(controlDims).isNotEmpty().allMatch(n -> n.startsWith("}")).contains("}Clients");
	}

}
