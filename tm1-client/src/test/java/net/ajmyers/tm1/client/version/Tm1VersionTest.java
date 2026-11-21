package net.ajmyers.tm1.client.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Tm1VersionTest {

	@Test
	void keepsTheTextAsReported() {
		assertThat(Tm1Version.parse(" 11.8.03500.6\n")).hasToString("11.8.03500.6");
	}

	@Test
	void comparesPartsNumerically() {
		assertThat(Tm1Version.parse("11.8.03500.6")).isEqualTo(Tm1Version.parse("11.8.3500.6"));
		assertThat(Tm1Version.parse("11.8.03500.6")).isGreaterThan(Tm1Version.parse("11.8.02800.9"));
		assertThat(Tm1Version.parse("11.10")).isGreaterThan(Tm1Version.parse("11.9.99999"));
		assertThat(Tm1Version.parse("12.0.0")).isGreaterThan(Tm1Version.parse("11.8.03500.6"));
	}

	@Test
	void treatsMissingPartsAsZero() {
		assertThat(Tm1Version.parse("11.8")).isEqualTo(Tm1Version.parse("11.8.0.0"));
		assertThat(Tm1Version.parse("11.8")).hasSameHashCodeAs(Tm1Version.parse("11.8.0.0"));
		assertThat(Tm1Version.parse("11.8.1")).isGreaterThan(Tm1Version.parse("11.8"));
	}

	@Test
	void answersRangeQuestions() {
		Tm1Version v = Tm1Version.parse("11.8.03500.6");
		assertThat(v.isAtLeast(Tm1Version.parse("11.8.03500.6"))).isTrue();
		assertThat(v.isAtLeast(Tm1Version.parse("11.8.02300"))).isTrue();
		assertThat(v.isAtLeast(Tm1Version.parse("11.8.03600"))).isFalse();
		assertThat(v.isBefore(Tm1Version.parse("12"))).isTrue();
		assertThat(v.isBefore(v)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "11.", ".8", "11..8", "v11.8", "11.8-beta", "99999999999" })
	void rejectsNonVersions(String text) {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Version.parse(text))
			.withMessageContaining("Not a TM1 version");
	}

}
