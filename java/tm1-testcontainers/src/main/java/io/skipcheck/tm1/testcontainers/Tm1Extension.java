// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.DockerClientFactory;

/**
 * The extension behind {@link RequiresTm1}: skips tests without Docker or a TM1 source,
 * and injects the shared {@link Tm1ServerContainer}.
 *
 * <p>
 * Our own extension rather than Testcontainers' {@code @Testcontainers}: the shared
 * container's lifetime is the JVM, not a test class, and it keeps this module independent
 * of which JUnit version Testcontainers' extension supports.
 */
final class Tm1Extension implements ExecutionCondition, ParameterResolver {

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		if (!DockerClientFactory.instance().isDockerAvailable()) {
			return ConditionEvaluationResult.disabled("Docker isn't available, so there's no TM1 to test against");
		}
		// Throws if a source is set but invalid: a wrong setting should fail, not skip
		Optional<Tm1Source> source = Tm1Source.discover();
		return source.isPresent() ? ConditionEvaluationResult.enabled("TM1 from " + source.get().describe())
				: ConditionEvaluationResult.disabled(RequiresTm1.NO_SOURCE_MESSAGE);
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == Tm1ServerContainer.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return Tm1ServerContainer.shared();
	}

}
