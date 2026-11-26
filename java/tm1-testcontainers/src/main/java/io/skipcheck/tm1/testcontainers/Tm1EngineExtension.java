// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.testcontainers.DockerClientFactory;

import io.skipcheck.tm1.client.version.Tm1Version;

/**
 * The extension behind {@link RequiresTm1Engine}: skips tests without Docker or a TM1 v12
 * source, and injects the shared {@link Tm1EngineContainer} and the test class's
 * {@link Tm1Database}.
 */
final class Tm1EngineExtension implements ExecutionCondition, ParameterResolver {

	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
		.create(Tm1EngineExtension.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		if (!DockerClientFactory.instance().isDockerAvailable()) {
			return ConditionEvaluationResult.disabled("Docker isn't available, so there's no TM1 to test against");
		}
		// Throws if a source is set but invalid: a wrong setting should fail, not skip
		Optional<Tm1EngineSource> source = Tm1EngineSource.discover();
		if (source.isEmpty()) {
			return ConditionEvaluationResult.disabled(RequiresTm1Engine.NO_SOURCE_MESSAGE);
		}
		String minimum = annotation(context).map(RequiresTm1Engine::minVersion).orElse("");
		if (!minimum.isBlank()) {
			Tm1Version running = Tm1EngineContainer.shared().getTm1Version();
			if (running.isBefore(Tm1Version.parse(minimum))) {
				return ConditionEvaluationResult.disabled("Needs TM1 " + minimum + " or later; the engine from "
						+ source.get().describe() + " is " + running);
			}
		}
		return ConditionEvaluationResult.enabled("TM1 v12 from " + source.get().describe());
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		Class<?> type = parameter.getParameter().getType();
		return type == Tm1EngineContainer.class || type == Tm1Database.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		Tm1EngineContainer engine = Tm1EngineContainer.shared();
		if (parameter.getParameter().getType() == Tm1EngineContainer.class) {
			return engine;
		}
		if (annotation(context).map(RequiresTm1Engine::shareDatabase).orElse(false)) {
			return engine.database(Tm1EngineContainer.DEFAULT_DATABASE);
		}
		// Kept with the outermost test class, so its nested classes share it, and JUnit
		// drops it when that class is done
		ExtensionContext owner = outermostClass(context);
		return owner.getStore(NAMESPACE)
			.computeIfAbsent(Tm1Database.class,
					key -> new OwnDatabase(engine, engine.createDatabase(databaseName(owner))),
					OwnDatabase.class).database;
	}

	/** The annotation on the test's method, or else on its class or an enclosing one. */
	private static Optional<RequiresTm1Engine> annotation(ExtensionContext context) {
		Optional<RequiresTm1Engine> found = context.getElement()
			.flatMap(element -> AnnotationSupport.findAnnotation(element, RequiresTm1Engine.class));
		Class<?> type = context.getTestClass().orElse(null);
		while (found.isEmpty() && type != null) {
			found = AnnotationSupport.findAnnotation(type, RequiresTm1Engine.class);
			type = type.getEnclosingClass();
		}
		return found;
	}

	private static ExtensionContext outermostClass(ExtensionContext context) {
		ExtensionContext owner = context;
		while (owner.getParent().flatMap(ExtensionContext::getTestClass).isPresent()) {
			owner = owner.getParent().orElseThrow();
		}
		return owner;
	}

	/**
	 * The class's name, as far as a database's name allows, and a few random characters.
	 */
	static String databaseName(ExtensionContext owner) {
		String type = owner.getTestClass().map(Class::getSimpleName).orElse("test");
		return databaseName(type, Integer.toString(RANDOM.nextInt(36 * 36 * 36 * 36), 36));
	}

	static String databaseName(String type, String suffix) {
		String letters = type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
		String start = letters.isEmpty() ? "test" : letters.substring(0, Math.min(letters.length(), 24));
		return start + "_" + ("0000" + suffix).substring(suffix.length());
	}

	/** A test class's database, dropped when JUnit closes the class's store. */
	private record OwnDatabase(Tm1EngineContainer engine, Tm1Database database) implements AutoCloseable {

		@Override
		public void close() {
			if (engine.isRunning()) {
				engine.dropDatabase(database);
			}
		}

	}

}
