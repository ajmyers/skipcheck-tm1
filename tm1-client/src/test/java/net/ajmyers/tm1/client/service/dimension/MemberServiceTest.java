package net.ajmyers.tm1.client.service.dimension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Element;
import net.ajmyers.tm1.client.model.ElementType;
import net.ajmyers.tm1.client.model.Hierarchy;
import net.ajmyers.tm1.client.model.Level;
import net.ajmyers.tm1.client.model.Member;
import net.ajmyers.tm1.client.odata.Filter;
import net.ajmyers.tm1.client.odata.ODataQuery;

/**
 * MemberService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/MemberServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class MemberServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String MEMBERS = "Dimensions('Region')/Hierarchies('Region')/Members";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsMembers() {
		tm1().json("{\"value\":[{\"Name\":\"West\",\"UniqueName\":\"[Region].[Region].[West]\"}]}");
		List<Member> members = conformance.call("members", "list", "Region", "Region",
				ODataQuery.none()
					.select(Member.NAME, Member.UNIQUE_NAME)
					.filter(Filter.raw("Type eq 1"))
					.orderBy(Member.NAME)
					.top(1));
		assertThat(members).extracting(Member::uniqueName).containsExactly("[Region].[Region].[West]");
		assertThat(tm1().last().key()).startsWith("GET " + MEMBERS + "?$select=Name,UniqueName&$filter=")
			.endsWith("&$orderby=Name&$top=1");
	}

	@Test
	void followsNextLinksAcrossPages() {
		tm1().json("{\"value\":[{\"Name\":\"a\"}],\"@odata.nextLink\":\"" + MEMBERS + "?$skiptoken=2\"}");
		tm1().json("{\"value\":[{\"Name\":\"b\"}]}");
		List<Member> members = conformance.call("members", "list", "Region", "Region", ODataQuery.none());
		assertThat(members).extracting(Member::name).containsExactly("a", "b");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.containsExactly(MEMBERS, MEMBERS + "?$skiptoken=2");
	}

	@Test
	void getsAMember() {
		tm1().json(
				"{\"@odata.etag\":\"W/\\\"2\\\"\",\"Name\":\"West Coast\",\"UniqueName\":\"[Region].[Region].[West Coast]\"}");
		Member member = conformance.call("members", "get", "Region", "Region", "West Coast",
				ODataQuery.none().select("Name", "UniqueName").expand("Element", e -> e.select("Type")));
		assertThat(member.name()).isEqualTo("West Coast");
		assertThat(tm1().last().target())
			.isEqualTo(MEMBERS + "('West%20Coast')?$select=Name,UniqueName&$expand=Element($select%3DType)");
	}

	@Test
	void quotesNamesWithApostrophesAndOtherCharacters() {
		tm1().json("{\"Name\":\"O'Neil & Co\"}");
		conformance.call("members", "get", "Bob's", "Bob's", "O'Neil & Co", ODataQuery.none());
		assertThat(tm1().last().target())
			.isEqualTo("Dimensions('Bob''s')/Hierarchies('Bob''s')/Members('O''Neil%20%26%20Co')");
	}

	@Test
	void aMissingMemberIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Member'.");
		Throwable error = conformance.fail("members", "get", "Region", "Region", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);

		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Member'.");
		Throwable children = conformance.fail("members", "children", "Region", "Region", "Nope", ODataQuery.none());
		assertThat(children).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsAMembersParent() {
		tm1().json("{\"Name\":\"Total\"}");
		Member parent = conformance.call("members", "parent", "Region", "Region", "West",
				ODataQuery.none().select("Name"));
		assertThat(parent.name()).isEqualTo("Total");
		assertThat(tm1().last().key()).isEqualTo("GET " + MEMBERS + "('West')/Parent?$select=Name");
	}

	@Test
	void aRootHasNoParent() {
		// TM1 answers a root's parent with no content
		tm1().status(204);
		Member none = conformance.call("members", "parent", "Region", "Region", "Total", ODataQuery.none());
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("GET " + MEMBERS + "('Total')/Parent");
	}

	@Test
	void readsAMembersChildren() {
		tm1().json("{\"value\":[{\"Name\":\"West\"},{\"Name\":\"East\"}]}");
		List<Member> children = conformance.call("members", "children", "Region", "Region", "Total",
				ODataQuery.none().select("Name"));
		assertThat(children).extracting(Member::name).containsExactly("West", "East");
		assertThat(tm1().last().key()).isEqualTo("GET " + MEMBERS + "('Total')/Children?$select=Name");

		tm1().json("{\"value\":[]}");
		List<Member> leaf = conformance.call("members", "children", "Region", "Region", "West", ODataQuery.none());
		assertThat(leaf).isEmpty();
	}

	@Test
	void readsAMembersElementLevelAndHierarchy() {
		tm1().json("{\"Name\":\"West\",\"Type\":\"Numeric\"}");
		Element element = conformance.call("members", "element", "Region", "Region", "West",
				ODataQuery.none().select("Name", "Type"));
		assertThat(element.type()).isEqualTo(ElementType.NUMERIC);
		assertThat(tm1().last().key()).isEqualTo("GET " + MEMBERS + "('West')/Element?$select=Name,Type");

		tm1().json("{\"Number\":2,\"Name\":\"Level002\",\"Cardinality\":3}");
		Level level = conformance.call("members", "level", "Region", "Region", "West", ODataQuery.none());
		assertThat(level.number()).isEqualTo(2);
		assertThat(tm1().last().key()).isEqualTo("GET " + MEMBERS + "('West')/Level");

		tm1().json("{\"Name\":\"Region\"}");
		Hierarchy hierarchy = conformance.call("members", "hierarchy", "Region", "Region", "West",
				ODataQuery.none().select("Name"));
		assertThat(hierarchy.name()).isEqualTo("Region");
		assertThat(tm1().last().key()).isEqualTo("GET " + MEMBERS + "('West')/Hierarchy?$select=Name");
	}

	@Test
	void refusesMissingNamesSendingNothing() {
		assertThat(conformance.fail("members", "get", "Region", "Region", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("members", "parent", null, "Region", "West", ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("members", "children", "Region", null, "West", ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
