package net.ajmyers.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Hierarchy;
import net.ajmyers.tm1.client.model.Level;
import net.ajmyers.tm1.client.model.Member;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * LevelService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/LevelServiceTest.json}, which
 * the Python, .NET and TypeScript clients replay.
 */
class LevelServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String L = "Dimensions('Region')/Hierarchies('Region')/Levels";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsLevels() {
		tm1().json("{\"value\":[{\"Number\":0,\"Name\":\"level000\",\"Cardinality\":1},"
				+ "{\"Number\":1,\"Name\":\"level001\",\"Cardinality\":2}]}");
		List<Level> levels = conformance.call("levels", "list", "Region", "Region",
				ODataQuery.none().select(Level.NUMBER, Level.NAME, Level.CARDINALITY));
		assertThat(levels).extracting(Level::number).containsExactly(0, 1);
		assertThat(levels).extracting(Level::cardinality).containsExactly(1, 2);
		assertThat(tm1().last().key()).isEqualTo("GET " + L + "?$select=Number,Name,Cardinality");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"Number\":0}],\"@odata.nextLink\":\"" + L + "?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"Number\":1}]}");
		List<Level> levels = conformance.call("levels", "list", "Region", "Region", ODataQuery.none());
		assertThat(levels).extracting(Level::number).containsExactly(0, 1);
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly(L, L + "?$skiptoken=1");
	}

	@Test
	void getsALevelByNumber() {
		tm1().json("{\"Number\":2,\"Name\":\"level002\",\"Cardinality\":3}");
		Level level = conformance.call("levels", "get", "Region", "Region", 2, ODataQuery.none());
		assertThat(level.cardinality()).isEqualTo(3);
		assertThat(tm1().last().key()).isEqualTo("GET " + L + "(2)");

		tm1().json("{\"Number\":0}");
		conformance.call("levels", "get", "O'Brien", "By Manager", 0, ODataQuery.none().select(Level.NUMBER));
		assertThat(tm1().last().target())
			.isEqualTo("Dimensions('O''Brien')/Hierarchies('By%20Manager')/Levels(0)?$select=Number");
	}

	@Test
	void aMissingLevelIsNotFound() {
		tm1().error(404, "278", "'9' can not be found in collection of type 'Level'.");
		Throwable error = conformance.fail("levels", "get", "Region", "Region", 9, ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void listsALevelsMembers() {
		tm1().json("{\"value\":[{\"Name\":\"North\"},{\"Name\":\"South\"}]}");
		List<Member> members = conformance.call("levels", "members", "Region", "Region", 1,
				ODataQuery.none().select(Member.NAME).top(2));
		assertThat(members).extracting(Member::name).containsExactly("North", "South");
		assertThat(tm1().last().key()).isEqualTo("GET " + L + "(1)/Members?$select=Name&$top=2");
	}

	@Test
	void navigatesToTheHierarchy() {
		tm1().json("{\"Name\":\"Region\"}");
		Hierarchy hierarchy = conformance.call("levels", "hierarchy", "Region", "Region", 0, ODataQuery.none());
		assertThat(hierarchy.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET " + L + "(0)/Hierarchy");
	}

	@Test
	void refusesMissingNames() {
		assertThat(conformance.fail("levels", "list", null, "Region", ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("levels", "get", "Region", null, 0, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
