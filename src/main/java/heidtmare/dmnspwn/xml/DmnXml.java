package heidtmare.dmnspwn.xml;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Hardened XML parsing (no DTDs, no external entities) and pretty serialization. */
public final class DmnXml {

    private static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private static final ErrorHandler STRICT = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    /** Factories are costly to look up and not thread-safe, so each thread keeps its own builder and serializer. */
    private static final ThreadLocal<DocumentBuilder> BUILDER = ThreadLocal.withInitial(DmnXml::newBuilder);
    private static final ThreadLocal<Transformer> SERIALIZER = ThreadLocal.withInitial(DmnXml::newSerializer);

    private DmnXml() {
    }

    public static Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new DmnFormatException("The document is empty");
        }
        DocumentBuilder builder = BUILDER.get();
        try {
            return builder.parse(new InputSource(new StringReader(stripBom(xml))));
        } catch (SAXParseException e) {
            throw new DmnFormatException("Malformed XML at line %d, column %d: %s"
                    .formatted(e.getLineNumber(), e.getColumnNumber(), e.getMessage()), e);
        } catch (SAXException | java.io.IOException e) {
            throw new DmnFormatException("Unable to read XML: " + e.getMessage(), e);
        } finally {
            builder.reset();
            builder.setErrorHandler(STRICT);
        }
    }

    public static Document newDocument() {
        return BUILDER.get().newDocument();
    }

    /** Serializes a whole document with an XML declaration. */
    public static String serialize(Document document) {
        return DECLARATION + write(document.getDocumentElement());
    }

    /** Serializes a single element (and its subtree) without an XML declaration. */
    public static String serialize(Element element) {
        return write(element);
    }

    private static String write(Element element) {
        stripIndentation(element);
        try {
            StringWriter out = new StringWriter();
            SERIALIZER.get().transform(new DOMSource(element), new StreamResult(out));
            return out.toString().strip() + "\n";
        } catch (TransformerException e) {
            throw new IllegalStateException("Unable to serialize XML", e);
        }
    }

    private static Transformer newSerializer() {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            return t;
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            builder.setErrorHandler(STRICT);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Removes whitespace-only text nodes from element-only content so the
     * serializer can re-indent without producing blank lines. Text inside
     * leaf elements (e.g. {@code <text>}) is untouched.
     */
    private static void stripIndentation(Element element) {
        NodeList children = element.getChildNodes();
        boolean hasElementChild = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                hasElementChild = true;
                break;
            }
        }
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripIndentation((Element) child);
            } else if (hasElementChild && child.getNodeType() == Node.TEXT_NODE
                    && child.getNodeValue().isBlank()) {
                toRemove.add(child);
            }
        }
        toRemove.forEach(element::removeChild);
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f;
    }

    private static String stripBom(String xml) {
        return xml.startsWith("﻿") ? xml.substring(1) : xml;
    }
}
