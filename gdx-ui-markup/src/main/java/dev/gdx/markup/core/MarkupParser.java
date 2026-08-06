package dev.gdx.markup.core;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * GL-free strict markup parser. Uses JDK SAX with every external-input feature disabled:
 * DOCTYPE declarations, external and parameter entities, and external DTD loading are all
 * rejected up front. All allocation and size limits are enforced during parsing.
 */
public final class MarkupParser {
    /** Maximum UTF-8 input size. */
    public static final int MAX_INPUT_BYTES = 1_048_576;
    /** Maximum element count per document. */
    public static final int MAX_ELEMENTS = 10_000;
    /** Maximum element nesting depth. */
    public static final int MAX_DEPTH = 64;
    /** Maximum attribute value length in characters. */
    public static final int MAX_ATTRIBUTE_VALUE = 4_096;
    /** Maximum text content length in characters. */
    public static final int MAX_TEXT = 4_096;

    private final int maxInputBytes;
    private final int maxElements;
    private final int maxDepth;
    private final int maxAttributeValue;
    private final int maxText;
    private final Set<String> extraTags;

    /** Creates a parser with the default bounded limits. */
    public MarkupParser() {
        this(MAX_INPUT_BYTES, MAX_ELEMENTS, MAX_DEPTH, MAX_ATTRIBUTE_VALUE, MAX_TEXT, Set.of());
    }

    /**
     * Creates a parser that also accepts the listed custom tags (common attributes only; a
     * {@link MarkupRegistry} factory provides the actor at build time).
     */
    public MarkupParser(Set<String> extraTags) {
        this(MAX_INPUT_BYTES, MAX_ELEMENTS, MAX_DEPTH, MAX_ATTRIBUTE_VALUE, MAX_TEXT, extraTags);
    }

    /** Creates a parser with explicit bounded limits. */
    public MarkupParser(
            int maxInputBytes, int maxElements, int maxDepth, int maxAttributeValue, int maxText) {
        this(maxInputBytes, maxElements, maxDepth, maxAttributeValue, maxText, Set.of());
    }

    /** Creates a parser with explicit bounded limits and custom tags. */
    public MarkupParser(int maxInputBytes, int maxElements, int maxDepth,
            int maxAttributeValue, int maxText, Set<String> extraTags) {
        this.maxInputBytes = maxInputBytes;
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
        this.maxAttributeValue = maxAttributeValue;
        this.maxText = maxText;
        this.extraTags = Set.copyOf(Objects.requireNonNull(extraTags, "extraTags"));
    }

    /** Parses one bounded markup document into an immutable validated element tree. */
    public MarkupDocument parse(String xml) {
        Objects.requireNonNull(xml, "xml");
        byte[] utf8 = xml.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > maxInputBytes) {
            throw new MarkupException(MarkupException.Kind.TOO_LARGE, "", 0, 0,
                    "markup input of " + utf8.length + " bytes exceeds the "
                            + maxInputBytes + "-byte limit");
        }
        try {
            Handler handler = new Handler(utf8.length);
            XMLReader reader = reader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.document();
        } catch (SAXParseException failure) {
            throw new MarkupException(MarkupException.Kind.MALFORMED_XML,
                    "", failure.getLineNumber(), failure.getColumnNumber(),
                    failure.getMessage());
        } catch (SAXException failure) {
            if (failure.getCause() instanceof MarkupException markup) {
                throw markup;
            }
            throw new MarkupException(MarkupException.Kind.MALFORMED_XML, "", 0, 0,
                    failure.getMessage());
        } catch (IOException | ParserConfigurationException impossible) {
            throw new IllegalStateException("SAX parser unavailable", impossible);
        }
    }

    private static XMLReader reader()
            throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/namespaces", false);
        SAXParser parser = factory.newSAXParser();
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return parser.getXMLReader();
    }

    /** SAX handler that builds the validated element tree and enforces every bound. */
    private final class Handler extends DefaultHandler {
        private final int byteLength;
        private final Deque<Frame> stack = new ArrayDeque<>();
        private final Set<String> ids = new HashSet<>();
        private final List<Element> roots = new ArrayList<>();
        private final Map<String, Integer> sameTagSiblings = new LinkedHashMap<>();
        private Locator locator;
        private Element root;
        private int elements;

        private Handler(int byteLength) {
            this.byteLength = byteLength;
        }

        @Override public void setDocumentLocator(Locator newLocator) {
            locator = newLocator;
        }

        @Override public void startElement(String uri, String localName, String qName,
                Attributes attributes) throws SAXException {
            String tag = qName;
            int line = locator == null ? 0 : locator.getLineNumber();
            int column = locator == null ? 0 : locator.getColumnNumber();
            String path = pathOf(tag);
            if (++elements > maxElements) {
                throw tooLarge("document exceeds the " + maxElements + "-element limit", path,
                        line, column);
            }
            if (stack.size() >= maxDepth) {
                throw tooLarge("nesting exceeds the " + maxDepth + "-level limit", path,
                        line, column);
            }
            TagSpec spec = TagSpec.require(tag, extraTags, path, line, column);
            LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
            for (int index = 0; index < attributes.getLength(); index++) {
                String name = attributes.getQName(index);
                String value = attributes.getValue(index);
                if (value.length() > maxAttributeValue) {
                    throw tooLarge("attribute \"" + name + "\" exceeds the "
                            + maxAttributeValue + "-character limit", path, line, column);
                }
                if (!spec.allows(name)) {
                    throw new MarkupException(MarkupException.Kind.UNKNOWN_ATTRIBUTE, path, line,
                            column, "unknown attribute \"" + name + "\" on <" + tag + ">");
                }
                // data-* attributes carry no value grammar beyond the allows() suffix gate.
                TagSpec.ValueKind kind = spec.attributes().get(name);
                String failure = kind == null ? null : TagSpec.validate(kind, value);
                if (failure != null) {
                    throw new MarkupException(MarkupException.Kind.INVALID_VALUE, path, line,
                            column, "invalid value for \"" + name + "\": " + failure);
                }
                attrs.put(name, value);
            }
            for (String required : spec.required()) {
                if (!attrs.containsKey(required)) {
                    throw new MarkupException(MarkupException.Kind.MISSING_ATTRIBUTE, path, line,
                            column, "<" + tag + "> requires attribute \"" + required + "\"");
                }
            }
            String id = attrs.get("id");
            if (id != null && !ids.add(id)) {
                throw new MarkupException(MarkupException.Kind.DUPLICATE_ID, path, line, column,
                        "duplicate id \"" + id + "\"");
            }
            stack.push(new Frame(tag, path, attrs, line, column));
        }

        @Override public void characters(char[] ch, int start, int length) throws SAXException {
            Frame frame = stack.peek();
            if (frame == null) {
                return;
            }
            if (!frame.children.isEmpty()) {
                String text = new String(ch, start, length);
                if (!text.isBlank()) {
                    throw new MarkupException(MarkupException.Kind.INVALID_VALUE, frame.path,
                            line(), column(), "mixed text content is not supported in <"
                                    + frame.tag + ">");
                }
                return;
            }
            frame.text.append(ch, start, length);
            if (frame.text.length() > maxText) {
                throw tooLarge("text content exceeds the " + maxText
                        + "-character limit", frame.path, line(), column());
            }
        }

        @Override public void endElement(String uri, String localName, String qName)
                throws SAXException {
            Frame frame = stack.pop();
            Element element = frame.build(ids);
            if (stack.isEmpty()) {
                roots.add(element);
            } else {
                stack.peek().children.add(element);
            }
        }

        @Override public void endDocument() throws SAXException {
            if (roots.isEmpty()) {
                throw new MarkupException(MarkupException.Kind.MALFORMED_XML, "", line(), column(),
                        "document contains no root element");
            }
            Element documentRoot = roots.get(0);
            if (!"ui".equals(documentRoot.tag()) && !"table".equals(documentRoot.tag())) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, documentRoot.tag(),
                        documentRoot.line(), documentRoot.column(),
                        "root element must be <ui> or <table>, got <" + documentRoot.tag() + ">");
            }
            if (roots.size() > 1) {
                throw new MarkupException(MarkupException.Kind.MALFORMED_XML, documentRoot.tag(),
                        documentRoot.line(), documentRoot.column(),
                        "document contains multiple root elements");
            }
            root = documentRoot;
        }

        MarkupDocument document() {
            return new MarkupDocument(root, byteLength);
        }

        @Override public void error(SAXParseException failure) throws SAXException {
            throw failure;
        }

        @Override public void fatalError(SAXParseException failure) throws SAXException {
            throw failure;
        }

        private MarkupException tooLarge(String message, String path, int line, int column) {
            return new MarkupException(MarkupException.Kind.TOO_LARGE, path, line, column,
                    message);
        }

        private int line() {
            return locator == null ? 0 : locator.getLineNumber();
        }

        private int column() {
            return locator == null ? 0 : locator.getColumnNumber();
        }

        private String pathOf(String tag) {
            Integer count = sameTagSiblings.merge(tag, 1, Integer::sum) - 1;
            String segment = count == 0 ? tag : tag + "[" + count + "]";
            if (stack.isEmpty()) {
                return segment;
            }
            return stack.peek().path + "/" + segment;
        }
    }

    /** One in-progress element during parsing. */
    private static final class Frame {
        private final String tag;
        private final String path;
        private final LinkedHashMap<String, String> attrs;
        private final List<Element> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final int line;
        private final int column;

        private Frame(String tag, String path, LinkedHashMap<String, String> attrs,
                int line, int column) {
            this.tag = tag;
            this.path = path;
            this.attrs = attrs;
            this.line = line;
            this.column = column;
        }

        private Element build(Set<String> ids) {
            String content = text.toString().strip();
            if (!content.isEmpty() && !children.isEmpty()) {
                throw new MarkupException(MarkupException.Kind.INVALID_VALUE, path, line, column,
                        "mixed text content is not supported in <" + tag + ">");
            }
            String declaredText = attrs.get("text");
            String textValue = content.isEmpty() ? declaredText : content;
            String id = attrs.get("id");
            String name = attrs.get("name");
            String label = attrs.get("label");
            LinkedHashMap<String, String> semanticAttrs = new LinkedHashMap<>(attrs);
            if (content.isEmpty() && declaredText != null) {
                semanticAttrs.put("text", declaredText);
            } else if (!content.isEmpty()) {
                semanticAttrs.put("text", content);
            }
            List<String> classes = splitClasses(attrs.get("class"));
            return new Element(tag, id, name, label, textValue, semanticAttrs, classes,
                    List.copyOf(children), line, column);
        }

        private static List<String> splitClasses(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return List.of(value.toLowerCase(Locale.ROOT).split("\\s+"));
        }
    }
}
