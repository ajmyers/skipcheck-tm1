package net.ajmyers.tm1.testcontainers;

import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The extension behind {@link RequiresTm1Web}: skips tests without a TM1 Web source, and
 * injects the shared {@link Tm1WebContainer}. Docker and the TM1 source are checked, and
 * {@link Tm1ServerContainer} injected, by {@link Tm1Extension}, which
 * {@link RequiresTm1Web} includes, so the two never both resolve the same parameter.
 */
final class Tm1WebExtension implements ExecutionCondition, ParameterResolver {

	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		// Throws if a source is set but invalid: a wrong setting should fail, not skip
		Optional<Tm1WebSource> source = Tm1WebSource.discover();
		return source.isPresent() ? ConditionEvaluationResult.enabled("TM1 Web from " + source.get().describe())
				: ConditionEvaluationResult.disabled(RequiresTm1Web.NO_SOURCE_MESSAGE);
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == Tm1WebContainer.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return Tm1WebContainer.shared();
	}

}
