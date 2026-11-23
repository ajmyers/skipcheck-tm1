package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.SQLDataSource;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.process.SqlDataSourceService},
 * quoted in its Javadoc.
 */
final class SqlDataSourceServiceExamples {

	Flux<String> names(Tm1Client tm1) {
		// @start region = names
		// The ODBC sources a process could read from, on TM1's host
		return tm1.sqlDataSources().list(ODataQuery.none()).mapNotNull(SQLDataSource::name);
		// @end
	}

	private SqlDataSourceServiceExamples() {
	}

}
