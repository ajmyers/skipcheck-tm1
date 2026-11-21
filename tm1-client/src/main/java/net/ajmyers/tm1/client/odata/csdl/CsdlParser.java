package net.ajmyers.tm1.client.odata.csdl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses CSDL XML ({@code $metadata}) into a {@link CsdlDocument}.
 *
 * <p>
 * The parser is hardened for documents from servers you may not control: DOCTYPE
 * declarations are refused, so external entities and entity expansion can't apply. It's
 * lenient about content instead. Unknown elements, attributes and annotation terms are
 * ignored or kept as data, as the CSDL specification asks of clients.
 */
public final class CsdlParser {

	/** The {@code edmx} namespace of OData 4.0 and 4.01. */
	public static final String EDMX_NAMESPACE = "http://docs.oasis-open.org/odata/ns/edmx";

	/** The {@code edm} namespace of OData 4.0 and 4.01. */
	public static final String EDM_NAMESPACE = "http://docs.oasis-open.org/odata/ns/edm";

	private static final Set<String> CONSTANT_KINDS = Set.of("String", "Bool", "Int", "Decimal", "Float", "Date",
			"DateTimeOffset", "TimeOfDay", "Duration", "Guid", "Binary", "EnumMember", "Path", "PropertyPath",
			"NavigationPropertyPath", "AnnotationPath", "ModelElementPath");

	private CsdlParser() {
	}

	/** Parses a CSDL document from text. */
	public static CsdlDocument parse(String xml) {
		return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Parses a CSDL document. The stream isn't closed.
	 * @throws CsdlParseException if it isn't well-formed XML, or not a CSDL document
	 */
	public static CsdlDocument parse(InputStream xml) {
		Element root;
		try {
			root = newBuilder().parse(xml).getDocumentElement();
		}
		catch (SAXException e) {
			throw new CsdlParseException("Not a readable CSDL document: " + e.getMessage(), e);
		}
		catch (IOException e) {
			throw new CsdlParseException("Couldn't read the CSDL document: " + e.getMessage(), e);
		}
		if (!"Edmx".equals(root.getLocalName()) || !EDMX_NAMESPACE.equals(root.getNamespaceURI())) {
			throw new CsdlParseException(
					"Not a CSDL document: the root element is " + root.getTagName() + ", not edmx:Edmx");
		}
		List<CsdlSchema> schemas = new ArrayList<>();
		for (Element dataServices : children(root, "DataServices")) {
			for (Element schema : children(dataServices, "Schema")) {
				schemas.add(schema(schema));
			}
		}
		return new CsdlDocument(attr(root, "Version", "4.0"), schemas);
	}

	private static CsdlSchema schema(Element e) {
		List<CsdlEntityType> entityTypes = new ArrayList<>();
		List<CsdlComplexType> complexTypes = new ArrayList<>();
		List<CsdlEnumType> enumTypes = new ArrayList<>();
		List<CsdlOperation> operations = new ArrayList<>();
		CsdlEntityContainer container = null;
		Map<String, List<CsdlAnnotation>> external = new LinkedHashMap<>();
		for (Element child : elements(e)) {
			switch (child.getLocalName()) {
				case "EntityType" -> entityTypes.add(entityType(child));
				case "ComplexType" -> complexTypes.add(complexType(child));
				case "EnumType" -> enumTypes.add(enumType(child));
				case "Action" -> operations.add(operation(child, CsdlOperation.Kind.ACTION));
				case "Function" -> operations.add(operation(child, CsdlOperation.Kind.FUNCTION));
				case "EntityContainer" -> container = container(child);
				case "Annotations" -> external.computeIfAbsent(required(child, "Target"), t -> new ArrayList<>())
					.addAll(annotations(child));
				default -> {
					// TypeDefinition, Term and anything newer: not used by the client
				}
			}
		}
		return new CsdlSchema(required(e, "Namespace"), optionalAttr(e, "Alias"), entityTypes, complexTypes, enumTypes,
				operations, container, annotations(e), external);
	}

	private static CsdlEntityType entityType(Element e) {
		List<String> key = new ArrayList<>();
		for (Element k : children(e, "Key")) {
			for (Element ref : children(k, "PropertyRef")) {
				key.add(required(ref, "Name"));
			}
		}
		return new CsdlEntityType(required(e, "Name"), optionalAttr(e, "BaseType"), bool(e, "Abstract", false),
				bool(e, "OpenType", false), bool(e, "HasStream", false), key, properties(e), navigationProperties(e),
				annotations(e));
	}

	private static CsdlComplexType complexType(Element e) {
		return new CsdlComplexType(required(e, "Name"), optionalAttr(e, "BaseType"), bool(e, "Abstract", false),
				bool(e, "OpenType", false), properties(e), navigationProperties(e), annotations(e));
	}

	private static List<CsdlProperty> properties(Element e) {
		List<CsdlProperty> result = new ArrayList<>();
		for (Element p : children(e, "Property")) {
			result.add(new CsdlProperty(required(p, "Name"), CsdlTypeRef.parse(required(p, "Type")),
					bool(p, "Nullable", true), optionalAttr(p, "DefaultValue"), annotations(p)));
		}
		return result;
	}

	private static List<CsdlNavigationProperty> navigationProperties(Element e) {
		List<CsdlNavigationProperty> result = new ArrayList<>();
		for (Element p : children(e, "NavigationProperty")) {
			result.add(new CsdlNavigationProperty(required(p, "Name"), CsdlTypeRef.parse(required(p, "Type")),
					bool(p, "Nullable", true), optionalAttr(p, "Partner"), bool(p, "ContainsTarget", false),
					annotations(p)));
		}
		return result;
	}

	private static CsdlEnumType enumType(Element e) {
		List<CsdlEnumType.Member> members = new ArrayList<>();
		for (Element m : children(e, "Member")) {
			String value = optionalAttr(m, "Value");
			Long parsed;
			try {
				parsed = value == null ? null : Long.valueOf(value.strip());
			}
			catch (NumberFormatException ex) {
				throw new CsdlParseException(
						"Enum member " + required(m, "Name") + " has a non-integer Value: " + value, ex);
			}
			members.add(new CsdlEnumType.Member(required(m, "Name"), parsed, annotations(m)));
		}
		return new CsdlEnumType(required(e, "Name"), attr(e, "UnderlyingType", "Edm.Int32"), bool(e, "IsFlags", false),
				members, annotations(e));
	}

	private static CsdlOperation operation(Element e, CsdlOperation.Kind kind) {
		List<CsdlParameter> parameters = new ArrayList<>();
		for (Element p : children(e, "Parameter")) {
			parameters.add(new CsdlParameter(required(p, "Name"), CsdlTypeRef.parse(required(p, "Type")),
					bool(p, "Nullable", true), annotations(p)));
		}
		CsdlTypeRef returnType = null;
		boolean returnNullable = true;
		for (Element r : children(e, "ReturnType")) {
			returnType = CsdlTypeRef.parse(required(r, "Type"));
			returnNullable = bool(r, "Nullable", true);
		}
		return new CsdlOperation(kind, required(e, "Name"), bool(e, "IsBound", false), bool(e, "IsComposable", false),
				optionalAttr(e, "EntitySetPath"), parameters, returnType, returnNullable, annotations(e));
	}

	private static CsdlEntityContainer container(Element e) {
		List<CsdlEntityContainer.EntitySet> sets = new ArrayList<>();
		List<CsdlEntityContainer.Singleton> singletons = new ArrayList<>();
		List<CsdlEntityContainer.ActionImport> actions = new ArrayList<>();
		List<CsdlEntityContainer.FunctionImport> functions = new ArrayList<>();
		for (Element child : elements(e)) {
			switch (child.getLocalName()) {
				case "EntitySet" -> sets.add(new CsdlEntityContainer.EntitySet(required(child, "Name"),
						required(child, "EntityType"), bindings(child), annotations(child)));
				case "Singleton" -> singletons.add(new CsdlEntityContainer.Singleton(required(child, "Name"),
						required(child, "Type"), bindings(child), annotations(child)));
				case "ActionImport" -> actions.add(new CsdlEntityContainer.ActionImport(required(child, "Name"),
						required(child, "Action"), optionalAttr(child, "EntitySet"), annotations(child)));
				case "FunctionImport" -> functions.add(new CsdlEntityContainer.FunctionImport(required(child, "Name"),
						required(child, "Function"), optionalAttr(child, "EntitySet"), annotations(child)));
				default -> {
					// Annotations are read below
				}
			}
		}
		return new CsdlEntityContainer(required(e, "Name"), sets, singletons, actions, functions, annotations(e));
	}

	private static Map<String, String> bindings(Element e) {
		Map<String, String> result = new LinkedHashMap<>();
		for (Element b : children(e, "NavigationPropertyBinding")) {
			result.put(required(b, "Path"), required(b, "Target"));
		}
		return result;
	}

	private static List<CsdlAnnotation> annotations(Element e) {
		List<CsdlAnnotation> result = new ArrayList<>();
		for (Element a : children(e, "Annotation")) {
			result.add(new CsdlAnnotation(required(a, "Term"), optionalAttr(a, "Qualifier"), value(a)));
		}
		return result;
	}

	/**
	 * An annotation's or property value's value: an attribute shorthand, or its first
	 * expression child.
	 */
	private static CsdlValue value(Element e) {
		for (String kind : CONSTANT_KINDS) {
			if (e.hasAttribute(kind)) {
				return new CsdlValue.Constant(kind, e.getAttribute(kind));
			}
		}
		for (Element child : elements(e)) {
			if (!"Annotation".equals(child.getLocalName())) {
				return expression(child);
			}
		}
		return new CsdlValue.Empty();
	}

	private static CsdlValue expression(Element e) {
		String kind = e.getLocalName();
		if (CONSTANT_KINDS.contains(kind)) {
			return new CsdlValue.Constant(kind, e.getTextContent().strip());
		}
		if ("Collection".equals(kind)) {
			List<CsdlValue> items = new ArrayList<>();
			for (Element child : elements(e)) {
				if (!"Annotation".equals(child.getLocalName())) {
					items.add(expression(child));
				}
			}
			return new CsdlValue.Collection(items);
		}
		if ("Record".equals(kind)) {
			Map<String, CsdlValue> properties = new LinkedHashMap<>();
			for (Element pv : children(e, "PropertyValue")) {
				properties.put(required(pv, "Property"), value(pv));
			}
			return new CsdlValue.Record(properties);
		}
		return new CsdlValue.Other(kind);
	}

	private static List<Element> elements(Element parent) {
		List<Element> result = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node instanceof Element element && element.getLocalName() != null) {
				result.add(element);
			}
		}
		return result;
	}

	private static List<Element> children(Element parent, String localName) {
		List<Element> result = new ArrayList<>();
		for (Element e : elements(parent)) {
			if (localName.equals(e.getLocalName())) {
				result.add(e);
			}
		}
		return result;
	}

	private static String attr(Element e, String name, String fallback) {
		return e.hasAttribute(name) ? e.getAttribute(name) : fallback;
	}

	/** An optional attribute: its value, or null if it isn't there. */
	private static @Nullable String optionalAttr(Element e, String name) {
		return e.hasAttribute(name) ? e.getAttribute(name) : null;
	}

	private static String required(Element e, String name) {
		if (!e.hasAttribute(name)) {
			throw new CsdlParseException(e.getLocalName() + " has no " + name + " attribute");
		}
		return e.getAttribute(name);
	}

	private static boolean bool(Element e, String name, boolean fallback) {
		return e.hasAttribute(name) ? "true".equalsIgnoreCase(e.getAttribute(name).strip()) : fallback;
	}

	private static DocumentBuilder newBuilder() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			// Refusing DOCTYPEs rules out external entities and entity expansion entirely
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			return factory.newDocumentBuilder();
		}
		catch (ParserConfigurationException e) {
			throw new IllegalStateException("The JDK's XML parser doesn't support secure processing", e);
		}
	}

}
