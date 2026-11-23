package io.skipcheck.tm1.client.metadata;

import java.util.NavigableSet;
import java.util.TreeSet;

import io.skipcheck.tm1.csdl.CsdlDocument;
import io.skipcheck.tm1.csdl.CsdlEntityContainer;
import io.skipcheck.tm1.csdl.CsdlEnumType;
import io.skipcheck.tm1.csdl.CsdlNavigationProperty;
import io.skipcheck.tm1.csdl.CsdlOperation;
import io.skipcheck.tm1.csdl.CsdlParameter;
import io.skipcheck.tm1.csdl.CsdlProperty;
import io.skipcheck.tm1.csdl.CsdlSchema;
import io.skipcheck.tm1.csdl.CsdlStructuredType;

/**
 * A document's API surface as a sorted set of one-line signatures, such as
 * {@code Property ibm.tm1.api.v1.Cube/Name: Edm.String}. Diffing two sets shows what an
 * API version added or removed, in a form that's easy to read in a report.
 */
final class Signatures {

	private Signatures() {
	}

	static NavigableSet<String> of(CsdlDocument doc) {
		NavigableSet<String> out = new TreeSet<>();
		for (CsdlSchema schema : doc.schemas()) {
			String ns = schema.namespace() + ".";
			schema.entityTypes().forEach(t -> type(out, doc, "EntityType", ns, t));
			schema.complexTypes().forEach(t -> type(out, doc, "ComplexType", ns, t));
			for (CsdlEnumType e : schema.enumTypes()) {
				out.add("EnumType " + ns + e.name());
				e.members().forEach(m -> out.add("EnumMember " + ns + e.name() + "/" + m.name()));
			}
			for (CsdlOperation op : schema.operations()) {
				StringBuilder params = new StringBuilder();
				for (CsdlParameter p : op.parameters()) {
					params.append(params.length() == 0 ? "" : ", ")
						.append(p.name())
						.append(": ")
						.append(doc.qualify(p.type().name()));
				}
				out.add((op.kind() == CsdlOperation.Kind.ACTION ? "Action " : "Function ") + ns + op.name() + "("
						+ params + ")" + (op.returnType() == null ? "" : ": " + op.returnType()));
			}
		}
		doc.entityContainer().ifPresent(c -> container(out, doc, c));
		return out;
	}

	private static void type(NavigableSet<String> out, CsdlDocument doc, String kind, String ns, CsdlStructuredType t) {
		out.add(kind + " " + ns + t.name() + (t.baseType() == null ? "" : " : " + doc.qualify(t.baseType())));
		for (CsdlProperty p : t.properties()) {
			out.add("Property " + ns + t.name() + "/" + p.name() + ": " + p.type());
		}
		for (CsdlNavigationProperty n : t.navigationProperties()) {
			out.add("Navigation " + ns + t.name() + "/" + n.name() + ": " + n.type());
		}
	}

	private static void container(NavigableSet<String> out, CsdlDocument doc, CsdlEntityContainer c) {
		c.entitySets().forEach(s -> out.add("EntitySet " + s.name() + ": " + doc.qualify(s.entityType())));
		c.singletons().forEach(s -> out.add("Singleton " + s.name() + ": " + doc.qualify(s.type())));
		c.actionImports().forEach(a -> out.add("ActionImport " + a.name() + ": " + doc.qualify(a.action())));
		c.functionImports().forEach(f -> out.add("FunctionImport " + f.name() + ": " + doc.qualify(f.function())));
	}

}
