package io.skipcheck.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Rules for the starter's Spring components, which unit tests can't catch because they
 * build classes by hand: a mistake here only shows when an application's context starts.
 */
class ArchitectureTest {

	private static final JavaClasses STARTER = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("io.skipcheck.tm1.spring.boot");

	/**
	 * A component with several constructors only starts if exactly one is marked
	 * {@code @Autowired}; otherwise Spring looks for a default constructor and the
	 * context fails.
	 */
	@Test
	void marksExactlyOneConstructorOfAComponentWithSeveral() {
		List<String> ambiguous = STARTER.stream()
			.filter(c -> c.isAnnotatedWith(Component.class) || c.isMetaAnnotatedWith(Component.class))
			.filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT) && !c.isInterface())
			.filter(c -> c.getConstructors().size() > 1)
			.filter(c -> c.getConstructors().stream().filter(ArchitectureTest::autowired).count() != 1)
			.map(JavaClass::getName)
			.sorted()
			.toList();
		assertThat(ambiguous).as("components with several constructors and not exactly one @Autowired").isEmpty();
	}

	/**
	 * Spring injects through a method only by its parameters, so an {@code @Autowired}
	 * method with none is always an annotation that slid off a constructor when code was
	 * inserted between them.
	 */
	@Test
	void neverAutowiresAMethodWithoutParameters() {
		List<String> misplaced = STARTER.stream()
			.flatMap(c -> c.getMethods().stream())
			.filter(m -> m.isAnnotatedWith(Autowired.class) && m.getRawParameterTypes().isEmpty())
			.map(JavaMethod::getFullName)
			.sorted()
			.toList();
		assertThat(misplaced).as("@Autowired methods with no parameters").isEmpty();
	}

	private static boolean autowired(JavaConstructor constructor) {
		return constructor.isAnnotatedWith(Autowired.class);
	}

}
