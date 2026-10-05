package heidtmare.dmnspwn.scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import javax.xml.XMLConstants;

import org.camunda.feel.syntaxtree.Val;
import org.camunda.feel.syntaxtree.ValBoolean;
import org.camunda.feel.syntaxtree.ValContext;
import org.camunda.feel.syntaxtree.ValDate;
import org.camunda.feel.syntaxtree.ValDateTime;
import org.camunda.feel.syntaxtree.ValDayTimeDuration;
import org.camunda.feel.syntaxtree.ValList;
import org.camunda.feel.syntaxtree.ValLocalDateTime;
import org.camunda.feel.syntaxtree.ValLocalTime;
import org.camunda.feel.syntaxtree.ValNumber;
import org.camunda.feel.syntaxtree.ValString;
import org.camunda.feel.syntaxtree.ValTime;
import org.camunda.feel.syntaxtree.ValYearMonthDuration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import heidtmare.dmnspwn.eval.Feel;
import heidtmare.dmnspwn.eval.Scope;
import heidtmare.dmnspwn.eval.Values;
import heidtmare.dmnspwn.xml.DmnFormatException;
import heidtmare.dmnspwn.xml.DmnXml;

/**
 * Reads and writes scenarios in the DMN TCK test case format ({@code testCases}), so test files can be exchanged
 * with other DMN engines. Values are converted between FEEL text and typed TCK values ({@code xsd:decimal},
 * {@code xsd:date}, ..., lists of {@code item}s and contexts of {@code component}s).
 */
public final class TestCases {

    public static final String NS = "http://www.omg.org/spec/DMN/20160719/testcase";
    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    private TestCases() {
    }

    // ---- reading ---------------------------------------------------------------------------------

    /** The decision test cases of a TCK file; test cases of BKMs or decision services are skipped. */
    public static List<Scenario> read(String xml) {
        Element root = DmnXml.parse(xml).getDocumentElement();
        if (!"testCases".equals(root.getLocalName())) {
            throw new DmnFormatException("Not a DMN test case file: the root element must be <testCases>");
        }
        List<Scenario> scenarios = new ArrayList<>();
        for (Element testCase : children(root, "testCase")) {
            String type = testCase.getAttribute("type");
            if (!type.isEmpty() && !"decision".equals(type)) {
                continue;
            }
            Map<String, String> inputs = new LinkedHashMap<>();
            for (Element input : children(testCase, "inputNode")) {
                inputs.put(input.getAttribute("name"), feel(input));
            }
            Map<String, String> expected = new LinkedHashMap<>();
            for (Element result : children(testCase, "resultNode")) {
                List<Element> holder = children(result, "expected");
                if (!holder.isEmpty()) {
                    expected.put(result.getAttribute("name"), feel(holder.getFirst()));
                }
            }
            scenarios.add(new Scenario(name(testCase, scenarios.size() + 1), inputs, expected));
        }
        return scenarios;
    }

    private static String name(Element testCase, int position) {
        if (!testCase.getAttribute("name").isBlank()) {
            return testCase.getAttribute("name");
        }
        List<Element> description = children(testCase, "description");
        if (!description.isEmpty() && !description.getFirst().getTextContent().isBlank()) {
            return description.getFirst().getTextContent().strip();
        }
        return testCase.getAttribute("id").isBlank() ? "Test " + position : testCase.getAttribute("id");
    }

    /** The value held by an {@code inputNode}, {@code expected}, {@code item} or {@code component} as FEEL text. */
    private static String feel(Element holder) {
        List<Element> value = children(holder, "value");
        if (!value.isEmpty()) {
            return literal(value.getFirst());
        }
        List<Element> list = children(holder, "list");
        if (!list.isEmpty()) {
            StringJoiner items = new StringJoiner(", ", "[", "]");
            children(list.getFirst(), "item").forEach(item -> items.add(feel(item)));
            return items.toString();
        }
        List<Element> components = children(holder, "component");
        if (!components.isEmpty()) {
            StringJoiner entries = new StringJoiner(", ", "{", "}");
            for (Element c : components) {
                String key = c.getAttribute("name");
                entries.add((Feel.isIdentifier(key) ? key : Values.format(new ValString(key))) + ": " + feel(c));
            }
            return entries.toString();
        }
        return "null";
    }

    private static String literal(Element value) {
        if ("true".equals(value.getAttributeNS(XSI, "nil"))) {
            return "null";
        }
        String text = value.getTextContent();
        String type = value.getAttributeNS(XSI, "type");
        type = type.substring(type.indexOf(':') + 1);
        return switch (type) {
            case "", "string", "anyURI" -> Values.format(new ValString(text));
            case "boolean" -> text.strip();
            case "date" -> "date(" + quote(text) + ")";
            case "time" -> "time(" + quote(text) + ")";
            case "dateTime" -> "date and time(" + quote(text) + ")";
            case "duration", "dayTimeDuration", "yearMonthDuration" -> "duration(" + quote(text) + ")";
            case "decimal", "double", "float", "integer", "int", "long", "short", "byte", "nonNegativeInteger",
                 "positiveInteger", "negativeInteger", "nonPositiveInteger", "unsignedLong", "unsignedInt",
                 "unsignedShort", "unsignedByte" -> number(text);
            default -> throw new DmnFormatException("Unsupported test value type '" + type + "'");
        };
    }

    private static String number(String text) {
        try {
            return new BigDecimal(text.strip()).toPlainString();
        } catch (NumberFormatException e) {
            throw new DmnFormatException("'" + text + "' is not a number");
        }
    }

    private static String quote(String text) {
        return Values.format(new ValString(text.strip()));
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                result.add(e);
            }
        }
        return result;
    }

    // ---- writing ---------------------------------------------------------------------------------

    /** The scenarios as a TCK file; every value must be a FEEL expression without free variables. */
    public static String write(String modelName, List<Scenario> scenarios, Feel feel) {
        Document doc = DmnXml.newDocument();
        Element root = doc.createElementNS(NS, "testCases");
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XSI);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsd", XSD);
        doc.appendChild(root);
        append(root, "modelName").setTextContent(modelName);
        for (int i = 0; i < scenarios.size(); i++) {
            Scenario s = scenarios.get(i);
            Element testCase = append(root, "testCase");
            testCase.setAttribute("id", String.valueOf(i + 1));
            if (!s.name().isEmpty()) {
                testCase.setAttribute("name", s.name());
                append(testCase, "description").setTextContent(s.name());
            }
            s.inputs().forEach((name, text) -> {
                Element input = append(testCase, "inputNode");
                input.setAttribute("name", name);
                write(input, value(feel, s, name, text));
            });
            s.expected().forEach((name, text) -> {
                Element result = append(testCase, "resultNode");
                result.setAttribute("name", name);
                result.setAttribute("type", "decision");
                write(append(result, "expected"), value(feel, s, name, text));
            });
        }
        return DmnXml.serialize(doc);
    }

    private static Val value(Feel feel, Scenario s, String element, String text) {
        Feel.Result r = feel.evaluate(text == null || text.isBlank() ? "null" : text, Scope.empty());
        if (r.failed() || !r.warnings().isEmpty()) {
            throw new InvalidScenarioException("Test '" + s.name() + "', " + element + ": "
                    + (r.failed() ? r.error() : String.join("; ", r.warnings())));
        }
        return r.value();
    }

    private static void write(Element holder, Val v) {
        switch (v) {
            case ValList l -> {
                Element list = append(holder, "list");
                Values.items(l).forEach(item -> write(append(list, "item"), item));
            }
            case ValContext c -> Values.entries(c).forEach((key, entry) -> {
                Element component = append(holder, "component");
                component.setAttribute("name", key);
                write(component, entry);
            });
            default -> {
                Element value = append(holder, "value");
                if (Values.isNull(v)) {
                    value.setAttributeNS(XSI, "xsi:nil", "true");
                    return;
                }
                String type = switch (v) {
                    case ValNumber n -> "decimal";
                    case ValString s -> "string";
                    case ValBoolean b -> "boolean";
                    case ValDate d -> "date";
                    case ValLocalTime t -> "time";
                    case ValTime t -> "time";
                    case ValLocalDateTime t -> "dateTime";
                    case ValDateTime t -> "dateTime";
                    case ValDayTimeDuration d -> "duration";
                    case ValYearMonthDuration d -> "duration";
                    default -> throw new InvalidScenarioException(
                            "A " + Values.typeName(v) + " value cannot be stored in a test: " + Values.format(v));
                };
                value.setAttributeNS(XSI, "xsi:type", "xsd:" + type);
                value.setTextContent(Values.literal(v));
            }
        }
    }

    private static Element append(Element parent, String localName) {
        Element e = parent.getOwnerDocument().createElementNS(NS, localName);
        parent.appendChild(e);
        return e;
    }
}
