// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers.examples;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.testcontainers.CognosContainer;
import io.skipcheck.tm1.testcontainers.CognosSource;
import io.skipcheck.tm1.testcontainers.RequiresCognos;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import io.skipcheck.tm1.testcontainers.Tm1Source;

/**
 * <b>Three users, three TM1 security profiles</b>, through Cognos (CAM) security: an
 * administrator, an analyst who may write to a cube, and a viewer who may only read it.
 *
 * <p>
 * The users and their groups are declared on the {@link CognosContainer};
 * {@code withCamSecurity} runs TM1 against it, makes {@code admin} a TM1 administrator,
 * and adds the groups to TM1. The test then gives the groups their cube rights, as a
 * model's security setup would, and logs on as each user. Cognos takes a couple of
 * minutes to start, so this class has one test.
 */
@RequiresCognos
class CamSecurityExampleTest {

	@Test
	void eachUserGetsTheirGroupsRights(CognosSource cognosSource) {
		Tm1Source tm1Source = Tm1Source.discover().orElseThrow();
		try (var cognos = new CognosContainer(cognosSource).withUser("admin", "adminpw", "Admins")
			.withUser("analyst", "analystpw", "Analysts", "Readers")
			.withUser("viewer", "viewerpw", "Readers");
				var tm1 = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, "admin")) {
			tm1.start();

			try (var admin = new Tm1Rest(tm1, cognos.camAuthorization("admin"));
					var analyst = new Tm1Rest(tm1, cognos.camAuthorization("analyst"));
					var viewer = new Tm1Rest(tm1, cognos.camAuthorization("viewer"))) {
				// TM1 knows users by CAMID, and puts them in their Cognos groups at logon
				assertThat(admin.get("ActiveUser?$select=Type")).contains("\"Type\":\"Admin\"");
				assertThat(analyst.get("ActiveUser/Groups?$select=Name"))
					.contains("CAMID(\\\"LDAP:g:cn=analysts,ou=groups\\\")")
					.contains("CAMID(\\\"LDAP:g:cn=readers,ou=groups\\\")");

				// The administrator builds a cube and its security: Analysts write,
				// Readers read
				assertThat(admin.post("ExecuteProcessWithReturn", setUpSecurity()).statusCode()).isEqualTo(201);

				assertThat(analyst.post("Cubes('Sales')/tm1.Update", write("150")).statusCode()).isEqualTo(204);
				assertThat(viewer
					.post("ExecuteMDX?$expand=Cells($select=Value)",
							"{\"MDX\":\"SELECT {[Measure].[Amount]} ON 0, {[Region].[North]} ON 1 FROM [Sales]\"}")
					.body()).contains("\"Value\":150");
				var refused = viewer.post("Cubes('Sales')/tm1.Update", write("999"));
				assertThat(refused.statusCode()).isEqualTo(400);
				assertThat(refused.body()).contains("CubeCellWriteStatusCubeNoWriteAccess");
			}
		}
	}

	/**
	 * A TurboIntegrator process that creates the Sales cube and gives the groups access.
	 */
	private static String setUpSecurity() {
		String analysts = "CAMID(\\\"LDAP:g:cn=analysts,ou=groups\\\")";
		String readers = "CAMID(\\\"LDAP:g:cn=readers,ou=groups\\\")";
		String prolog = String.join("\\n", "DimensionCreate('Region');",
				"DimensionElementInsert('Region', '', 'North', 'N');", "DimensionCreate('Measure');",
				"DimensionElementInsert('Measure', '', 'Amount', 'N');", "CubeCreate('Sales', 'Region', 'Measure');",
				"If(CubeExists('}CubeSecurity') = 0);", "  CubeCreate('}CubeSecurity', '}Cubes', '}Groups');", "EndIf;",
				"CellPutS('WRITE', '}CubeSecurity', 'Sales', '" + analysts + "');",
				"CellPutS('READ', '}CubeSecurity', 'Sales', '" + readers + "');", "SecurityRefresh;");
		return "{\"Process\":{\"Name\":\"zSetUpSecurity\",\"PrologProcedure\":\"" + prolog + "\"}}";
	}

	private static String write(String value) {
		return "{\"Cells\":[{\"Tuple@odata.bind\":[\"Dimensions('Region')/Hierarchies('Region')/Elements('North')\","
				+ "\"Dimensions('Measure')/Hierarchies('Measure')/Elements('Amount')\"]}],\"Value\":\"" + value + "\"}";
	}

}
