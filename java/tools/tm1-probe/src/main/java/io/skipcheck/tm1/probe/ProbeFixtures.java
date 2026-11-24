// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

/**
 * The objects the probe creates, so that every type it can reach has an entity to walk
 * through and read: a dimension with a consolidation, attributes, an alias, an alternate
 * hierarchy and subsets; a measures dimension; a cube with rules, a value and both kinds
 * of view; processes with an ASCII and a view data source; a chore with a task; a group
 * and a user; cube and process attributes; a sandbox; an annotation; a cellset. Every
 * name starts with {@value #PREFIX}, and {@link #delete} removes them all.
 */
public final class ProbeFixtures {

	/** What every fixture's name starts with. */
	public static final String PREFIX = "SkipcheckProbe";

	private static final String DIM = PREFIX + "Dim";

	private static final String CUBE = PREFIX + "Cube";

	private static final String H = "Dimensions('" + DIM + "')/Hierarchies('" + DIM + "')";

	private static final List<String[]> STEPS = List.of(step("POST", "Dimensions", """
			{"Name": "%1$sDim", "Hierarchies": [{"Name": "%1$sDim",
			 "Elements": [{"Name": "%1$sA", "Type": "Numeric"}, {"Name": "%1$sB", "Type": "Numeric"},
			              {"Name": "%1$sTotal", "Type": "Consolidated"}],
			 "Edges": [{"ParentName": "%1$sTotal", "ComponentName": "%1$sA", "Weight": 1},
			           {"ParentName": "%1$sTotal", "ComponentName": "%1$sB", "Weight": 1}]}]}"""),
			step("POST", H + "/ElementAttributes", """
					{"Name": "%1$sText", "Type": "String"}"""), step("POST", H + "/ElementAttributes", """
					{"Name": "%1$sAlias", "Type": "Alias"}"""), step("POST", "Dimensions('%1$sDim')/Hierarchies", """
					{"Name": "%1$sAlternate", "Elements": [{"Name": "%1$sA", "Type": "Numeric"}]}"""),
			step("POST", H + "/Subsets", """
					{"Name": "%1$sStatic", "Elements@odata.bind": [
					 "Dimensions('%1$sDim')/Hierarchies('%1$sDim')/Elements('%1$sA')",
					 "Dimensions('%1$sDim')/Hierarchies('%1$sDim')/Elements('%1$sTotal')"]}"""),
			step("POST", H + "/Subsets", """
					{"Name": "%1$sDynamic", "Expression": "{TM1SUBSETALL([%1$sDim])}"}"""),
			step("POST", "Dimensions",
					"""
							{"Name": "%1$sMeasures", "Hierarchies": [{"Name": "%1$sMeasures",
							 "Elements": [{"Name": "%1$sValue", "Type": "Numeric"}, {"Name": "%1$sNote", "Type": "String"}]}]}"""),
			// A dimension no fixture cube has, for a binding to add
			step("POST", "Dimensions", """
					{"Name": "%1$sOther", "Hierarchies": [{"Name": "%1$sOther",
					 "Elements": [{"Name": "%1$sO", "Type": "Numeric"}]}]}"""),
			step("POST", "Cubes",
					"""
							{"Name": "%1$sCube", "Dimensions@odata.bind": ["Dimensions('%1$sDim')", "Dimensions('%1$sMeasures')"],
							 "Rules": "['%1$sA', '%1$sValue'] = N: 5;"}"""),
			step("POST", "Cubes('%1$sCube')/Views",
					"""
							{"@odata.type": "ibm.tm1.api.v1.NativeView", "Name": "%1$sNative",
							 "Columns": [{"Subset@odata.bind": "Dimensions('%1$sDim')/Hierarchies('%1$sDim')/Subsets('%1$sStatic')"}],
							 "Rows": [{"Subset": {"Hierarchy@odata.bind": "Dimensions('%1$sMeasures')/Hierarchies('%1$sMeasures')",
							                      "Expression": "{TM1SUBSETALL([%1$sMeasures])}"}}],
							 "Titles": []}"""),
			step("POST", "Cubes('%1$sCube')/Views", """
					{"@odata.type": "ibm.tm1.api.v1.MDXView", "Name": "%1$sMdx",
					 "MDX": "SELECT {[%1$sDim].[%1$sA]} ON 0 FROM [%1$sCube]"}"""),
			step("POST", "Cubes('%1$sCube')/tm1.Update", """
					{"Cells": [{"Tuple@odata.bind": [
					 "Dimensions('%1$sDim')/Hierarchies('%1$sDim')/Elements('%1$sB')",
					 "Dimensions('%1$sMeasures')/Hierarchies('%1$sMeasures')/Elements('%1$sValue')"]}],
					 "Value": "3"}"""),
			step("POST", "Processes",
					"""
							{"Name": "%1$sAscii", "PrologProcedure": "x = 1;",
							 "Parameters": [{"Name": "pValue", "Prompt": "A value", "Value": "1", "Type": "String"}],
							 "DataSource": {"Type": "ASCII", "asciiDecimalSeparator": ".", "asciiDelimiterChar": ",",
							  "asciiDelimiterType": "Character", "asciiHeaderRecords": 1, "asciiQuoteCharacter": "\\"",
							  "asciiThousandSeparator": ",", "dataSourceNameForClient": "probe.csv",
							  "dataSourceNameForServer": "probe.csv"},
							 "Variables": [{"Name": "vText", "Type": "String", "Position": 1, "StartByte": 0, "EndByte": 0}]}"""),
			step("POST", "Processes",
					"""
							{"Name": "%1$sView",
							 "DataSource": {"Type": "TM1CubeView", "dataSourceNameForServer": "%1$sCube",
							  "dataSourceNameForClient": "%1$sCube", "view": "%1$sNative"},
							 "Variables": [{"Name": "vDim", "Type": "String", "Position": 1, "StartByte": 0, "EndByte": 0},
							               {"Name": "vMeasure", "Type": "String", "Position": 2, "StartByte": 0, "EndByte": 0},
							               {"Name": "Value", "Type": "Numeric", "Position": 3, "StartByte": 0, "EndByte": 0}]}"""),
			step("POST", "Processes('%1$sAscii')/tm1.ExecuteWithReturn", """
					{"Parameters": [{"Name": "pValue", "Value": "1"}]}"""),
			step("POST", "Chores",
					"""
							{"Name": "%1$sChore", "StartTime": "2026-01-01T00:00:00Z", "DSTSensitive": false, "Active": false,
							 "ExecutionMode": "SingleCommit", "Frequency": "P1DT00H00M00S",
							 "Tasks": [{"Process@odata.bind": "Processes('%1$sAscii')", "Parameters": [{"Name": "pValue", "Value": "1"}]}]}"""),
			step("POST", "Groups", """
					{"Name": "%1$sGroup"}"""), step("POST", "Users", """
					{"Name": "%1$sUser", "Password": "probe", "Groups@odata.bind": ["Groups('%1$sGroup')"]}"""),
			step("POST", "CubeAttributes", """
					{"Name": "%1$sCubeAttribute", "Type": "String"}"""), step("POST", "ProcessAttributes", """
					{"Name": "%1$sProcessAttribute", "Type": "String"}"""), step("POST", "Sandboxes", """
					{"Name": "%1$sSandbox"}"""), step("POST", "Annotations",
					"""
							{"Text": "A probe note",
							 "ApplicationContext": [{"Facet@odata.bind": "ApplicationContextFacets('}Cubes')", "Value": "%1$sCube"}],
							 "DimensionalContext@odata.bind": [
							  "Dimensions('%1$sDim')/Hierarchies('%1$sDim')/Members('%1$sB')",
							  "Dimensions('%1$sMeasures')/Hierarchies('%1$sMeasures')/Members('%1$sValue')"],
							 "objectName": "%1$sCube"}"""));

	private static final List<String> DELETES = List.of("Chores('%1$sChore')", "Processes('%1$sAscii')",
			"Processes('%1$sView')", "Cubes('%1$sCube')", "Dimensions('%1$sDim')", "Dimensions('%1$sMeasures')",
			"Dimensions('%1$sOther')", "Users('%1$sUser')", "Groups('%1$sGroup')",
			"CubeAttributes('%1$sCubeAttribute')", "ProcessAttributes('%1$sProcessAttribute')",
			"Sandboxes('%1$sSandbox')");

	private ProbeFixtures() {
	}

	private static String[] step(String method, String path, String body) {
		return new String[] { method, path, body };
	}

	/**
	 * Creates the fixtures, after deleting any a previous run left.
	 * @param client the server's client
	 * @return entities to start walking from that TM1 doesn't list, by qualified type
	 * name: the cellset
	 * @throws IllegalStateException if TM1 refuses a fixture
	 */
	public static Map<String, String> create(ProbeClient client) {
		delete(client);
		for (String[] step : STEPS) {
			String path = step[1].formatted(PREFIX);
			JsonNode body = ProbeClient.JSON.readTree(step[2].formatted(PREFIX));
			ProbeClient.Answer answer = client.send(step[0], path, body);
			if (!answer.ok()) {
				throw new IllegalStateException("TM1 refused a fixture: " + step[0] + " " + path + " answered "
						+ answer.status() + ": " + answer.body());
			}
		}
		ProbeClient.Answer cellset = client.send("POST", "ExecuteMDX",
				ProbeClient.JSON.readTree(
						"""
								{"MDX": "SELECT {[%1$sDim].[%1$sA], [%1$sDim].[%1$sB]} ON 0, {[%1$sMeasures].[%1$sValue]} ON 1 FROM [%1$sCube]"}"""
							.formatted(PREFIX)));
		JsonNode id = cellset.body() == null ? null : cellset.body().get("ID");
		if (!cellset.ok() || id == null) {
			throw new IllegalStateException(
					"TM1 refused the fixture cellset: " + cellset.status() + ": " + cellset.body());
		}
		Map<String, String> seeds = new TreeMap<>();
		seeds.put("ibm.tm1.api.v1.Cellset", "Cellsets('" + id.asString() + "')");
		return seeds;
	}

	/**
	 * Deletes the fixtures; missing ones are skipped.
	 * @param client the server's client
	 */
	public static void delete(ProbeClient client) {
		// Deleting the cube leaves its annotations behind
		ProbeClient.Answer annotations = client.get("Annotations");
		if (annotations.ok() && annotations.body() != null) {
			for (JsonNode a : annotations.body().path("value")) {
				if (CUBE.equals(a.path("objectName").asString(""))) {
					client.send("DELETE", "Annotations('" + a.path("ID").asString() + "')", null);
				}
			}
		}
		for (String path : DELETES) {
			client.send("DELETE", path.formatted(PREFIX), null);
		}
	}

}
