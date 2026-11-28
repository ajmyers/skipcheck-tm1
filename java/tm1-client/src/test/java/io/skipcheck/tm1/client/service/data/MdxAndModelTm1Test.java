// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.model.Cube;
import io.skipcheck.tm1.client.model.Dimension;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * MDX statements, and the model and control cube and dimension functions
 * (MemberSetServiceTm1Test has the member sets).
 */
@LiveTm1
class MdxAndModelTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(LiveServer server) {
		tm1 = server.clientBuilder().build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void runsAnMdxStatementButNotCreateSet() {
		if (ServerAssumptions.isV12(tm1)) {
			assertThatThrownBy(() -> tm1.cellsets().executeStatement("SELECT {} ON 0 FROM [}ClientGroups]").block(WAIT))
				.as("TM1 v12 ExecuteMDXStatement only supports CREATE and DROP")
				.isInstanceOf(Tm1BadRequestException.class);
		}
		else {
			tm1.cellsets().executeStatement("SELECT {} ON 0 FROM [}ClientGroups]").block(WAIT);
		}
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
