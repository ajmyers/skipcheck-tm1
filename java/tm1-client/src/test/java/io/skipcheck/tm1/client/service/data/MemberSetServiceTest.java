// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ApiException;
import io.skipcheck.tm1.client.error.Tm1ErrorKind;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.CellsetAxis;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * MemberSetService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/MemberSetServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class MemberSetServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private static final String MDX = "{TM1SUBSETALL([Region].[Region])}";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void executesASetExpression() {
		tm1().json("{\"Ordinal\":3,\"Cardinality\":2,\"Tuples\":[{\"Ordinal\":0,\"Members\":[{\"Name\":\"North\"}]},"
				+ "{\"Ordinal\":1,\"Members\":[{\"Name\":\"South\"}]}]}");
		CellsetAxis set = conformance.call("memberSets", "execute", MDX,
				ODataQuery.none().expand("Tuples", t -> t.expand("Members", m -> m.select("Name"))));
		assertThat(set.ordinal()).isEqualTo(3);
		assertThat(set.cardinality()).isEqualTo(2);
		assertThat(set.tuples()).hasSize(2);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key())
			.isEqualTo("POST ExecuteMDXSetExpression?$expand=Tuples($expand%3DMembers($select%3DName))");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");
		assertThat(post.text()).isEqualTo("{\"MDX\":\"" + MDX + "\"}");

		tm1().json("{\"Ordinal\":4,\"Cardinality\":0}");
		CellsetAxis empty = conformance.call("memberSets", "execute", "{}", ODataQuery.none());
		assertThat(empty.cardinality()).isZero();
		assertThat(tm1().last().key()).isEqualTo("POST ExecuteMDXSetExpression");
		assertThat(tm1().last().text()).isEqualTo("{\"MDX\":\"{}\"}");
	}

	@Test
	void sendsQuotesAndBackslashesInTheExpressionAsTheyAre() {
		tm1().json("{\"Ordinal\":5,\"Cardinality\":1}");
		conformance.call("memberSets", "execute", "{[Region].[Region].[O'Neil \"\\\\ West]}", ODataQuery.none());
		assertThat(tm1().last().text()).isEqualTo("{\"MDX\":\"{[Region].[Region].[O'Neil \\\"\\\\\\\\ West]}\"}");
	}

	@Test
	void aBadExpressionIsRefused() {
		tm1().error(400, "278", "Syntax error at or near: '}', character position 1");
		Throwable error = conformance.fail("memberSets", "execute", "{", ODataQuery.none());
		assertThat(error).isInstanceOfSatisfying(Tm1ApiException.class, e -> {
			assertThat(e.kind()).isEqualTo(Tm1ErrorKind.OTHER);
			assertThat(e.status()).isEqualTo(400);
		});
	}

	@Test
	void listsMemberSets() {
		tm1().json("{\"value\":[{\"Ordinal\":3},{\"Ordinal\":4}]}");
		List<CellsetAxis> sets = conformance.call("memberSets", "list", ODataQuery.none().select("Ordinal").top(2));
		assertThat(sets).extracting(CellsetAxis::ordinal).containsExactly(3, 4);
		assertThat(tm1().last().key()).isEqualTo("GET MemberSets?$select=Ordinal&$top=2");

		tm1().json("{\"value\":[]}");
		List<CellsetAxis> none = conformance.call("memberSets", "list", ODataQuery.none());
		assertThat(none).isEmpty();
		assertThat(tm1().last().key()).isEqualTo("GET MemberSets");
	}

	@Test
	void getsAMemberSetByItsOrdinal() {
		tm1().json("{\"Ordinal\":3,\"Cardinality\":2}");
		CellsetAxis set = conformance.call("memberSets", "get", 3, ODataQuery.none().select("Ordinal", "Cardinality"));
		assertThat(set.cardinality()).isEqualTo(2);
		assertThat(tm1().last().key()).isEqualTo("GET MemberSets(3)?$select=Ordinal,Cardinality");

		tm1().json("{\"Ordinal\":0,\"Cardinality\":0}");
		conformance.call("memberSets", "get", 0, ODataQuery.none().expand("Tuples"));
		assertThat(tm1().last().key()).isEqualTo("GET MemberSets(0)?$expand=Tuples");
	}

	@Test
	void aDeletedMemberSetIsNotFound() {
		tm1().error(404, "278", "'3' can not be found in collection of type 'CellsetAxis'.");
		Throwable error = conformance.fail("memberSets", "get", 3, ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);

		tm1().error(404, "278", "'3' can not be found in collection of type 'CellsetAxis'.");
		Throwable delete = conformance.fail("memberSets", "delete", 3);
		assertThat(delete).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void deletesAMemberSet() {
		tm1().status(204);
		Object none = conformance.call("memberSets", "delete", 3);
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("DELETE MemberSets(3)");

		tm1().status(204);
		conformance.call("memberSets", "delete", 2147483647);
		assertThat(tm1().last().key()).isEqualTo("DELETE MemberSets(2147483647)");
	}

	@Test
	void refusesAMissingExpressionSendingNothing() {
		Throwable error = conformance.fail("memberSets", "execute", null, ODataQuery.none());
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
