package io.skipcheck.tm1.testcontainers;

import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The extension behind {@link RequiresPaw}: skips tests without a PAW source or license
 * acceptance, and injects the shared {@link PawContainer}. Docker and the TM1 source are
 * checked, and {@link Tm1ServerContainer} injected, by {@link Tm1Extension}, which
 * {@link RequiresPaw} includes.
 */
final class PawExtension implements ExecutionCondition, ParameterResolver {

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		// Throws if a source is set but invalid: a wrong setting should fail, not skip
		Optional<PawSource> source = PawSource.discover();
		if (source.isEmpty()) {
			return ConditionEvaluationResult.disabled(RequiresPaw.NO_SOURCE_MESSAGE);
		}
		if (!PawContainer.licenseAcceptedBySetting()) {
			return ConditionEvaluationResult.disabled(RequiresPaw.NO_LICENSE_MESSAGE);
		}
		return ConditionEvaluationResult.enabled("PAW from " + source.get().describe());
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == PawContainer.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return PawContainer.shared();
	}

}
