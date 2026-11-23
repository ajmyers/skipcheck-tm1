package io.skipcheck.tm1.client.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class FilterTest {

	private static final ODataProperty NAME = ODataProperty.of("Name");

	private static final ODataProperty LEVEL = ODataProperty.of("Level");

	@Test
	void comparesWithLiterals() {
		assertThat(NAME.eq("O'Neil")).hasToString("(Name eq 'O''Neil')");
		assertThat(LEVEL.ge(0)).hasToString("(Level ge 0)");
		assertThat(ODataProperty.of("Weight").lt(new BigDecimal("1.50"))).hasToString("(Weight lt 1.50)");
		assertThat(ODataProperty.of("Value").gt(2.5d)).hasToString("(Value gt 2.5)");
		assertThat(ODataProperty.of("Active").eq(true)).hasToString("(Active eq true)");
		assertThat(ODataProperty.of("Parent").isNull()).hasToString("(Parent eq null)");
		assertThat(ODataProperty.of("Type")
			.eq(new ODataLiterals.Raw(ODataLiterals.enumMember("ibm.tm1.api.v1.ElementType", "Numeric"))))
			.hasToString("(Type eq ibm.tm1.api.v1.ElementType'Numeric')");
	}

	@Test
	void parenthesizesEveryCombination() {
		Filter f = Filter.or(Filter.and(NAME.eq("a"), LEVEL.eq(0)), Filter.not(Filter.startsWith(NAME, "}")));
		assertThat(f).hasToString("(((Name eq 'a') and (Level eq 0)) or not(startswith(Name,'}')))");
		assertThat(Filter.and(NAME.eq("a"))).isEqualTo(NAME.eq("a"));
	}

	@Test
	void usesCanonicalFunctions() {
		assertThat(Filter.contains(NAME, "Q1")).hasToString("contains(Name,'Q1')");
		assertThat(Filter.endsWith(NAME, "'s")).hasToString("endswith(Name,'''s')");
		assertThat(Filter.in(NAME, "a", "b")).hasToString("(Name in ('a','b'))");
	}

	@Test
	void writesLambdas() {
		assertThat(Filter.any("Hierarchies", "h", h -> h.nav("Name").eq("}Clients")))
			.hasToString("Hierarchies/any(h:(h/Name eq '}Clients'))");
		assertThat(Filter.all("Tags", "t", t -> t.self().ne("x"))).hasToString("Tags/all(t:(t ne 'x'))");
	}

	@Test
	void refusesValuesWithoutAClearLiteral() {
		assertThatIllegalArgumentException().isThrownBy(() -> NAME.eq(new Object()));
		assertThatIllegalArgumentException().isThrownBy(() -> Filter.in(NAME));
		assertThatIllegalArgumentException().isThrownBy(() -> Filter.and());
		assertThatIllegalArgumentException().isThrownBy(() -> Filter.any("X", "1v", v -> v.self().eq(1)));
	}

	@Test
	void writesSpecialNumbers() {
		assertThat(ODataLiterals.of(Double.NaN)).isEqualTo("NaN");
		assertThat(ODataLiterals.of(Double.NEGATIVE_INFINITY)).isEqualTo("-INF");
		assertThat(ODataLiterals.of(1e-7)).isEqualTo("0.0000001");
		assertThat(ODataLiterals.of(12L)).isEqualTo("12");
	}

	@Test
	void writesAValueThatKnowsItsOwnLiteral() {
		ODataLiteral numeric = () -> "ibm.tm1.api.v1.ElementType'Numeric'";
		assertThat(ODataProperty.of("Type").eq(numeric)).hasToString("(Type eq ibm.tm1.api.v1.ElementType'Numeric')");
	}

}
