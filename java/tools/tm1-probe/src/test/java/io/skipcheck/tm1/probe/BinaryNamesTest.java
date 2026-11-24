// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class BinaryNamesTest {

	@Test
	void splitsRunsIntoCamelCaseWords() {
		assertThat(BinaryNames.words("TM1FormatString")).containsExactly("TM1", "Format", "String");
		assertThat(BinaryNames.words("getUIDataEv")).containsExactly("get", "UI", "Data", "Ev");
		assertThat(BinaryNames.words("XMLParser")).containsExactly("XML", "Parser");
		assertThat(BinaryNames.words("Name")).containsExactly("Name");
	}

	@Test
	void keepsEverySpanOfWordsThatStartsUpperCase() {
		byte[] bytes = "\0_ZN3tm17Process9getUIDataEv\0".getBytes(StandardCharsets.US_ASCII);
		assertThat(BinaryNames.extract(bytes)).contains("UIData", "UI", "Data", "DataEv", "UIDataEv", "Process")
			.doesNotContain("get", "getUIData", "ZN3tm17Process9getUIDataEv".substring(1));
	}

	@Test
	void readsUtf16LittleEndianTextAtEitherAlignment() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0x7f);
		out.writeBytes("ExpandAbove".getBytes(StandardCharsets.UTF_16LE));
		out.write(0x7f);
		out.writeBytes("Alias".getBytes(StandardCharsets.UTF_16LE));
		assertThat(BinaryNames.extract(out.toByteArray())).contains("ExpandAbove", "Expand", "Above", "Alias");
	}

	@Test
	void dropsSingleLettersAndSpansOverTheLimit() {
		String longName = "A" + "b".repeat(BinaryNames.MAX_LENGTH);
		byte[] bytes = ("X " + longName + " Ok").getBytes(StandardCharsets.US_ASCII);
		assertThat(BinaryNames.extract(bytes)).containsExactly("Ok");
	}

	@Test
	void isSortedAndTheSameForTheSameBytes() {
		byte[] bytes = "Zeta Alpha Beta Alpha".getBytes(StandardCharsets.US_ASCII);
		assertThat(BinaryNames.extract(bytes)).containsExactly("Alpha", "Beta", "Zeta")
			.isEqualTo(BinaryNames.extract(bytes.clone()));
	}

}
