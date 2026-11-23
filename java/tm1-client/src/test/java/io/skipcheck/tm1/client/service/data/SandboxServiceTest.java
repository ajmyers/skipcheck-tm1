package io.skipcheck.tm1.client.service.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.ConflictResolution;
import io.skipcheck.tm1.client.model.Sandbox;
import io.skipcheck.tm1.client.model.SandboxPatch;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * SandboxService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/SandboxServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
class SandboxServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void listsAndGetsSandboxes() {
		tm1().json("{\"value\":[{\"Name\":\"Best case\",\"IsLoaded\":true},{\"Name\":\"Worst\"}]}");
		List<Sandbox> all = conformance.call("sandboxes", "list", ODataQuery.none());
		assertThat(all).extracting(Sandbox::name).containsExactly("Best case", "Worst");
		assertThat(tm1().last().key()).isEqualTo("GET Sandboxes");

		tm1().json("{\"value\":[{\"Name\":\"Best case\"}]}");
		conformance.call("sandboxes", "list",
				ODataQuery.none().select("Name", "IsLoaded").filter(Filter.raw("IsLoaded eq true")));
		assertThat(tm1().last().target()).isEqualTo("Sandboxes?$select=Name,IsLoaded&$filter=IsLoaded%20eq%20true");

		tm1().json("{\"Name\":\"Best case\",\"IsLoaded\":false,\"IsActive\":false}");
		Sandbox sandbox = conformance.call("sandboxes", "get", "Best case", ODataQuery.none().select("Name"));
		assertThat(sandbox.isLoaded()).isFalse();
		assertThat(tm1().last().target()).isEqualTo("Sandboxes('Best%20case')?$select=Name");
	}

	@Test
	void aMissingSandboxIsNotFound() {
		tm1().error(404, "278", "'Joe's' can not be found in collection of type 'Sandbox'.");
		Throwable error = conformance.fail("sandboxes", "get", "Joe's", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
		assertThat(tm1().last().target()).isEqualTo("Sandboxes('Joe''s')");
	}

	@Test
	void createsUpdatesAndDeletes() {
		tm1().json(201, "{\"Name\":\"S\",\"IsLoaded\":true}");
		Sandbox created = conformance.call("sandboxes", "create",
				SandboxPatch.builder().name("S").includeInSandboxDimension(true).build());
		assertThat(created.name()).isEqualTo("S");
		assertThat(tm1().last().key()).isEqualTo("POST Sandboxes");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"S\",\"IncludeInSandboxDimension\":true}");

		tm1().status(204);
		conformance.call("sandboxes", "update", "S", SandboxPatch.builder().name("T").isActive(null).build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Sandboxes('S')");
		assertThat(tm1().last().text()).isEqualTo("{\"Name\":\"T\",\"IsActive\":null}");

		tm1().status(204);
		conformance.call("sandboxes", "delete", "T");
		assertThat(tm1().last().key()).isEqualTo("DELETE Sandboxes('T')");
	}

	@Test
	void aSandboxThatExistsIsAConflict() {
		tm1().error(409, "278", "'S' already exists");
		Throwable error = conformance.fail("sandboxes", "create", SandboxPatch.builder().name("S").build());
		assertThat(error).isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void runsEachSandboxAction() {
		for (String action : List.of("load", "unload", "discardChanges", "publish")) {
			tm1().status(204);
			conformance.call("sandboxes", action, "Best case");
			String name = Character.toUpperCase(action.charAt(0)) + action.substring(1);
			assertThat(tm1().last().key()).isEqualTo("POST Sandboxes('Best%20case')/tm1." + name);
			assertThat(tm1().last().text()).isEqualTo("{}");
		}
	}

	@Test
	void publishesAndMergesWithOptions() {
		tm1().status(204);
		conformance.call("sandboxes", "publish", "S", ConflictResolution.USING_SOURCE, true, false);
		assertThat(tm1().last().text())
			.isEqualTo("{\"ConflictResolution\":\"UsingSource\",\"AllowQueueing\":true,\"CleanAfter\":false}");

		tm1().status(204);
		conformance.call("sandboxes", "merge", "S", "Joe's");
		assertThat(tm1().last().key()).isEqualTo("POST Sandboxes('S')/tm1.Merge");
		assertThat(tm1().last().text()).isEqualTo("{\"Target@odata.bind\":\"Sandboxes('Joe''s')\"}");

		tm1().status(204);
		conformance.call("sandboxes", "merge", "S", "T", ConflictResolution.INDIVIDUALLY, false, true);
		assertThat(tm1().last().text()).isEqualTo("{\"Target@odata.bind\":\"Sandboxes('T')\","
				+ "\"ConflictResolution\":\"Individually\",\"AllowQueueing\":false,\"CleanAfter\":true}");
	}

	@Test
	void aPublishThatConflictsFails() {
		tm1().error(409, "278", "conflicts");
		Throwable error = conformance.fail("sandboxes", "publish", "S", ConflictResolution.USING_TARGET, false, false);
		assertThat(error).isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void refusesAMissingName() {
		Throwable error = conformance.fail("sandboxes", "load", (Object) null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
