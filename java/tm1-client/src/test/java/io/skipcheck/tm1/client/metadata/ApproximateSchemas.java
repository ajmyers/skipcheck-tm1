// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.skipcheck.tm1.client.version.Tm1Version;
import io.skipcheck.tm1.csdl.CsdlAnnotation;
import io.skipcheck.tm1.csdl.CsdlComplexType;
import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlEntityType;
import io.skipcheck.tm1.csdl.CsdlEnumType;
import io.skipcheck.tm1.csdl.CsdlOperation;
import io.skipcheck.tm1.csdl.CsdlParameter;
import io.skipcheck.tm1.csdl.CsdlRevision;
import io.skipcheck.tm1.csdl.CsdlSchema;
import io.skipcheck.tm1.csdl.CsdlValue;

/**
 * Approximate {@code $metadata} for older TM1 schema versions, made from a newer
 * document's {@code Core.Revisions} (plan §12.4).
 *
 * <p>
 * {@link #asOf} removes every element whose revisions say it was added after the given
 * schema version, and sets {@code Core.SchemaVersion} to that version. It's approximate:
 * it reflects additions only. Behavior that changed, elements later removed, and elements
 * without revision records (assumed to have always existed) aren't modeled. It's for
 * testing that the client handles a server lacking newer endpoints, with only one real
 * TM1 version available.
 */
public final class ApproximateSchemas {

	private ApproximateSchemas() {
	}

	/**
	 * The snapshot as an approximate server of schema {@code version}, e.g.
	 * {@code 11.8.20}.
	 */
	public static CsdlDocument snapshotAsOf(String version) {
		return asOf(MetadataSnapshot.load(), version);
	}

	/** {@code doc} without the elements added after schema {@code version}. */
	public static CsdlDocument asOf(CsdlDocument doc, String version) {
		Tm1Version target = Tm1Version.parse(version);
		List<CsdlSchema> schemas = new ArrayList<>();
		for (CsdlSchema s : doc.schemas()) {
			List<CsdlEntityType> entityTypes = keep(s.entityTypes(), CsdlEntityType::annotations, target).stream()
				.map(t -> new CsdlEntityType(t.name(), t.baseType(), t.isAbstract(), t.openType(), t.hasStream(),
						t.key(), keep(t.properties(), p -> p.annotations(), target),
						keep(t.navigationProperties(), n -> n.annotations(), target), t.annotations()))
				.toList();
			List<CsdlComplexType> complexTypes = keep(s.complexTypes(), CsdlComplexType::annotations, target).stream()
				.map(t -> new CsdlComplexType(t.name(), t.baseType(), t.isAbstract(), t.openType(),
						keep(t.properties(), p -> p.annotations(), target),
						keep(t.navigationProperties(), n -> n.annotations(), target), t.annotations()))
				.toList();
			List<CsdlEnumType> enumTypes = keep(s.enumTypes(), CsdlEnumType::annotations, target).stream()
				.map(e -> new CsdlEnumType(e.name(), e.underlyingType(), e.flags(),
						keep(e.members(), m -> m.annotations(), target), e.annotations()))
				.toList();
			List<CsdlOperation> operations = keep(s.operations(), CsdlOperation::annotations, target).stream()
				.map(o -> new CsdlOperation(o.kind(), o.name(), o.bound(), o.composable(), o.entitySetPath(),
						keepParameters(o, target), o.returnType(), o.returnNullable(), o.annotations()))
				.toList();
			CsdlEntityContainer c = s.entityContainer();
			CsdlEntityContainer container = c == null ? null
					: new CsdlEntityContainer(c.name(), keep(c.entitySets(), x -> x.annotations(), target),
							keep(c.singletons(), x -> x.annotations(), target),
							keep(c.actionImports(), x -> x.annotations(), target),
							keep(c.functionImports(), x -> x.annotations(), target), c.annotations());
			schemas.add(new CsdlSchema(s.namespace(), s.alias(), entityTypes, complexTypes, enumTypes, operations,
					container, withSchemaVersion(s.annotations(), version), s.externalAnnotations()));
		}
		return new CsdlDocument(doc.version(), schemas);
	}

	private static List<CsdlParameter> keepParameters(CsdlOperation o, Tm1Version target) {
		if (!o.bound()) {
			return keep(o.parameters(), p -> p.annotations(), target);
		}
		// Never drop a bound operation's binding parameter
		List<CsdlParameter> result = new ArrayList<>();
		result.add(o.parameters().get(0));
		result.addAll(keep(o.callerParameters(), p -> p.annotations(), target));
		return result;
	}

	private static <T> List<T> keep(List<T> items, Function<T, List<CsdlAnnotation>> annotations, Tm1Version target) {
		return items.stream()
			// Keep what was added at or before the target, and anything with no "added"
			// record
			.filter(item -> CsdlRevision.addedIn(annotations.apply(item))
				.map(v -> !target.isBefore(Tm1Version.parse(v)))
				.orElse(true))
			.toList();
	}

	private static List<CsdlAnnotation> withSchemaVersion(List<CsdlAnnotation> annotations, String version) {
		List<CsdlAnnotation> result = new ArrayList<>();
		for (CsdlAnnotation a : annotations) {
			result.add(a.term().equals(CsdlDocument.SCHEMA_VERSION_TERM)
					? new CsdlAnnotation(a.term(), a.qualifier(), new CsdlValue.Constant("String", version)) : a);
		}
		return result;
	}

}
