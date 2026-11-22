package net.ajmyers.tm1.testcontainers;

import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The extension behind {@link RequiresCognos}: skips tests without a Cognos source, and
 * injects the {@link CognosSource} found. Docker and the TM1 source are checked by
 * {@link Tm1Extension}, which {@link RequiresCognos} includes.
 */
final class CognosExtension implements ExecutionCondition, ParameterResolver {

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		// Throws if a source is set but invalid: a wrong setting should fail, not skip
		Optional<CognosSource> source = CognosSource.discover();
		return source.isPresent() ? ConditionEvaluationResult.enabled("Cognos from " + source.get().describe())
				: ConditionEvaluationResult.disabled(RequiresCognos.NO_SOURCE_MESSAGE);
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == CognosSource.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return CognosSource.discover().orElseThrow(() -> new IllegalStateException(RequiresCognos.NO_SOURCE_MESSAGE));
	}

}
