// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
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
		.importPackages("io.skipcheck.tm1.client");

	private static final DescribedPredicate<JavaClass> SERVICES = DescribedPredicate.describe(
			"services (classes named *Service in io.skipcheck.tm1.client.service..)",
			c -> c.getPackageName().startsWith("io.skipcheck.tm1.client.service")
					&& c.getSimpleName().endsWith("Service"));

	private static final List<String> MONO_BLOCKING = List.of("block", "blockOptional", "toFuture");

	private static final List<String> FLUX_BLOCKING = List.of("blockFirst", "blockLast", "toIterable", "toStream");

	@Test
	void neverBlocksOnAReactiveChain() {
		noClasses().should()
			.callMethodWhere(
					DescribedPredicate.describe("a method that blocks on a Mono or Flux", ArchitectureTest::blocks))
			.orShould()
			.callMethod(Thread.class, "sleep", long.class)
			.because("the client is reactive only: blocking an event loop thread stalls every request on it, "
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
							+ "variables; requests use a URI already encoded by ODataPath and ODataQuery")
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
			.resideInAPackage("io.skipcheck.tm1.client.odata..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage("io.skipcheck.tm1.client", "io.skipcheck.tm1.client.session..",
					"io.skipcheck.tm1.client.version..")
			.because("the odata layer is TM1-agnostic: OData correctness lives in one place and doesn't "
					+ "depend on sessions, requests or TM1 versions")
			.check(LIBRARY);
	}

	@Test
	void servicesAreStatelessFinalClassesBuiltFromASession() {
		classes().that(SERVICES)
			.should()
			.haveModifier(JavaModifier.FINAL)
			.andShould(
					ArchCondition.from(DescribedPredicate.describe("have one public constructor, taking a Tm1Session",
							(JavaClass c) -> c.getConstructors().size() == 1 && c.getConstructors()
								.stream()
								.allMatch(k -> k.getModifiers().contains(JavaModifier.PUBLIC)
										&& k.getRawParameterTypes().size() == 1
										&& k.getRawParameterTypes().get(0).isEquivalentTo(Tm1Session.class)))))
			.andShould(ArchCondition
				.from(DescribedPredicate.describe("hold no state but the session (static final constants apart)",
						(JavaClass c) -> c.getFields()
							.stream()
							.allMatch(f -> f.getModifiers().contains(JavaModifier.FINAL)
									&& (f.getModifiers().contains(JavaModifier.STATIC)
											|| f.getRawType().isEquivalentTo(Tm1Session.class))))))
			.because("services are free to create and safe to share: everything they need is the session")
			.allowEmptyShould(true)
			.check(LIBRARY);
	}

	@Test
	void serviceMethodsAreReactiveAndDeclareTheirEndpoints() {
		methods().that()
			.areDeclaredInClassesThat(SERVICES)
			.and()
			.arePublic()
			.should(ArchCondition.from(DescribedPredicate.describe("return a Mono or a Flux",
					(JavaMethod m) -> m.getRawReturnType().isEquivalentTo(Mono.class)
							|| m.getRawReturnType().isEquivalentTo(Flux.class))))
			.andShould(ArchCondition.from(DescribedPredicate.describe("declare the endpoints they implement",
					(JavaMethod m) -> m.isAnnotatedWith(Tm1Endpoint.class)
							|| m.isAnnotatedWith(Tm1Endpoint.List.class))))
			.because("each service method is one REST call that never blocks, and the coverage gate "
					+ "counts what's implemented from @Tm1Endpoint")
			.allowEmptyShould(true)
			.check(LIBRARY);
	}

	private static boolean blocks(JavaMethodCall call) {
		String name = call.getName();
		return (call.getTargetOwner().isAssignableTo(Mono.class) && MONO_BLOCKING.contains(name))
				|| (call.getTargetOwner().isAssignableTo(Flux.class) && FLUX_BLOCKING.contains(name));
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
