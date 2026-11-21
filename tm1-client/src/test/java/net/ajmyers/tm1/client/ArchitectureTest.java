package net.ajmyers.tm1.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Rules the library's code keeps, checked over its compiled classes. Each one is a
 * mistake that's easy to make, compiles, passes most tests, and goes wrong in production;
 * its {@code because} says why. The rules are strict: nothing breaks them today, so any
 * new break fails the build.
 */
class ArchitectureTest {

	private static final JavaClasses LIBRARY = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("net.ajmyers.tm1.client");

	private static final List<String> MONO_BLOCKING = List.of("block", "blockOptional", "toFuture");

	private static final List<String> FLUX_BLOCKING = List.of("blockFirst", "blockLast", "toIterable", "toStream");

	@Test
	void neverBlocksOnAReactiveChain() {
		noClasses().should()
			.callMethodWhere(
					DescribedPredicate.describe("a method that blocks on a Mono or Flux", ArchitectureTest::blocks))
			.orShould()
			.callMethod(Thread.class, "sleep", long.class)
			.because("the client is reactive only (plan Q9): blocking an event loop thread stalls every request on it, "
					+ "and callers who want to block call block() themselves")
			.check(LIBRARY);
	}

	@Test
	void neverStartsWorkNobodySubscribedTo() {
		noClasses().should()
			.callMethodWhere(DescribedPredicate.describe("subscribe() with no subscriber, or with callbacks",
					ArchitectureTest::fireAndForget))
			.because(
					"a bare subscribe() starts work outside the caller's chain: its errors go nowhere, the caller can't "
							+ "cancel it, and it runs without the caller's Reactor Context. Return the Mono or Flux instead")
			.check(LIBRARY);
	}

	@Test
	void buildsRequestUrisRatherThanUsingUriTemplates() {
		noClasses().should()
			.callMethodWhere(DescribedPredicate.describe("WebClient's uri(String, ...) template methods",
					ArchitectureTest::uriTemplate))
			.because(
					"TM1 object names contain braces (control objects such as }Clients), which a URI template reads as "
							+ "variables; requests use a URI already encoded by ODataPath and ODataQuery (plan §16.2)")
			.check(LIBRARY);
	}

	@Test
	void logsThroughSlf4jOnly() {
		noClasses().should()
			.accessField(System.class, "out")
			.orShould()
			.accessField(System.class, "err")
			.orShould()
			.callMethod(Throwable.class, "printStackTrace")
			.orShould()
			.dependOnClassesThat()
			.resideInAnyPackage("java.util.logging..", "org.apache.commons.logging..")
			.because("logging goes through SLF4J with @Slf4j (CONTRIBUTING.md), so applications control where it goes "
					+ "and the library never writes to the console")
			.check(LIBRARY);
	}

	@Test
	void keepsTheODataLayerFreeOfTm1() {
		noClasses().that()
			.resideInAPackage("net.ajmyers.tm1.client.odata..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("net.ajmyers.tm1.client", "net.ajmyers.tm1.client.session..",
					"net.ajmyers.tm1.client.version..")
			.because("the odata layer is TM1-agnostic (plan §3.2): OData correctness lives in one place and doesn't "
					+ "depend on sessions, requests or TM1 versions")
			.check(LIBRARY);
	}

	private static boolean blocks(JavaMethodCall call) {
		String name = call.getName();
		return call.getTargetOwner().isAssignableTo(Mono.class) && MONO_BLOCKING.contains(name)
				|| call.getTargetOwner().isAssignableTo(Flux.class) && FLUX_BLOCKING.contains(name);
	}

	private static boolean fireAndForget(JavaMethodCall call) {
		if (!call.getName().equals("subscribe") || !(call.getTargetOwner().isAssignableTo(Mono.class)
				|| call.getTargetOwner().isAssignableTo(Flux.class))) {
			return false;
		}
		// subscribe(Subscriber) hands a chain on, as operators do; the others start one
		// detached
		List<String> parameters = call.getTarget().getRawParameterTypes().stream().map(t -> t.getName()).toList();
		return parameters.isEmpty() || parameters.get(0).equals("java.util.function.Consumer");
	}

	private static boolean uriTemplate(JavaMethodCall call) {
		return call.getName().equals("uri") && call.getTargetOwner().isAssignableTo(WebClient.UriSpec.class)
				&& !call.getTarget().getRawParameterTypes().isEmpty()
				&& call.getTarget().getRawParameterTypes().get(0).isEquivalentTo(String.class);
	}

}
