package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.SQLDataSource;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * SqlDataSourceService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/SqlDataSourceServiceTest.json}, which the Python, .NET
 * and TypeScript clients replay.
 */
class SqlDataSourceServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsTheDataSources() {
		tm1().json("{\"value\":[{\"Name\":\"Sales DW\",\"Description\":\"SQL Server\"},{\"Name\":\"Excel Files\"}]}");
		List<SQLDataSource> sources = conformance.call("sqlDataSources", "list", ODataQuery.none());
		assertThat(sources).extracting(SQLDataSource::name).containsExactly("Sales DW", "Excel Files");
		assertThat(tm1().last().key()).isEqualTo("GET SQLDataSources");

		tm1().json("{\"value\":[]}");
		conformance.call("sqlDataSources", "list",
				ODataQuery.none().select("Name").filter(Filter.raw("startswith(Name,'Sales')")).orderBy("Name"));
		assertThat(tm1().last().target())
			.isEqualTo("SQLDataSources?$select=Name&$filter=startswith(Name,'Sales')&$orderby=Name");
	}

	@Test
	void getsADataSource() {
		tm1().json("{\"Name\":\"Sales DW\",\"Description\":\"SQL Server\"}");
		SQLDataSource source = conformance.call("sqlDataSources", "get", "Sales DW", ODataQuery.none());
		assertThat(source.description()).isEqualTo("SQL Server");
		assertThat(tm1().last().target()).isEqualTo("SQLDataSources('Sales%20DW')");

		tm1().json("{\"Name\":\"O'Brien's\"}");
		conformance.call("sqlDataSources", "get", "O'Brien's", ODataQuery.none().select("Name"));
		assertThat(tm1().last().target()).isEqualTo("SQLDataSources('O''Brien''s')?$select=Name");
	}

	@Test
	void aMissingDataSourceIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'SQLDataSource'.");
		Throwable error = conformance.fail("sqlDataSources", "get", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void refusesAMissingName() {
		Throwable error = conformance.fail("sqlDataSources", "get", null, ODataQuery.none());
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
