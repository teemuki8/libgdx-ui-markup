package dev.gdx.markup.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** Maximum XML element, attribute, or custom-tag name length in characters. */
    public static final int MAX_NAME_LENGTH = 256;
    /** Maximum custom tags accepted by one parser configuration. */
    public static final int MAX_EXTRA_TAGS = 256;
    /** Maximum combined document-local and registered bundle component definitions. */
    public static final int MAX_COMPONENTS = 256;
    /** Maximum parameters declared by one component. */
    public static final int MAX_COMPONENT_PARAMETERS = 64;
    /** Maximum slots declared by one component. */
    public static final int MAX_COMPONENT_SLOTS = 32;
    /** Maximum parameter substitutions in one attribute or text value. */
    public static final int MAX_SUBSTITUTIONS_PER_VALUE = 32;
    /** Maximum nested component invocation depth. */
    public static final int MAX_COMPONENT_EXPANSION_DEPTH = 16;
    /** Maximum total nodes visited during component expansion. */
    public static final int MAX_EXPANSION_WORK = 100_000;

    private final int maxInputBytes;
    private final int maxElements;
    private final int maxDepth;
    private final int maxAttributeValue;
    private final int maxText;
    private final Set<String> extraTags;
    private final Map<String, String> componentBundles;

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
        this(maxInputBytes, maxElements, maxDepth, maxAttributeValue, maxText, extraTags, Map.of());
    }

    private MarkupParser(int maxInputBytes, int maxElements, int maxDepth,
            int maxAttributeValue, int maxText, Set<String> extraTags,
            Map<String, String> componentBundles) {
        this.maxInputBytes = maxInputBytes;
        this.maxElements = maxElements;
        this.maxDepth = maxDepth;
        this.maxAttributeValue = maxAttributeValue;
        this.maxText = maxText;
        this.extraTags = boundedExtraTags(extraTags);
        this.componentBundles = ComponentBundles.sources(componentBundles);
    }

    /**
     * Returns an independent parser with application-owned in-memory component bundles.
     * Keys are UpperCamel namespaces; values are strict {@code <ui><components>...</components></ui>}
     * documents. At most 16 bundles share this parser's byte/element budgets with the screen.
     * No path or URL is resolved. Replaces rather than appends previous bundle configuration.
     *
     * @param bundles namespace to XML source mapping, copied before return
     * @return parser retaining these limits and custom tags
     */
    public MarkupParser withComponentBundles(Map<String, String> bundles) {
        return new MarkupParser(maxInputBytes, maxElements, maxDepth, maxAttributeValue,
                maxText, extraTags, bundles);
    }

    /**
     * Parses one bounded markup document into an immutable validated element tree. The input's
     * UTF-8 byte length is checked against the configured limit before parsing.
     */
    public MarkupDocument parse(String xml) {
        return parse(xml, "<memory>");
    }

    private static Set<String> boundedExtraTags(Set<String> extraTags) {
        Set<String> tags = Objects.requireNonNull(extraTags, "extraTags");
        if (tags.size() > MAX_EXTRA_TAGS) {
            throw new IllegalArgumentException(
                    "extraTags exceeds " + MAX_EXTRA_TAGS + " entries");
        }
        for (String tag : tags) {
            Objects.requireNonNull(tag, "extraTags entry");
            if (tag.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "extra tag name exceeds " + MAX_NAME_LENGTH + " characters");
            }
        }
        return Set.copyOf(tags);
    }

    /** Parses one bounded in-memory document with an explicit diagnostic source identity. */
    public MarkupDocument parse(String xml, String sourceName) {
        String source = MarkupSourceLocation.validateSource(sourceName);
        Objects.requireNonNull(xml, "xml");
        byte[] utf8 = xml.getBytes(StandardCharsets.UTF_8);
        requireInputLimit(utf8.length, source);
        return parseUtf8(utf8.length, xml, source);
    }

    /**
     * Parses one bounded markup document read from {@code path}. At most
     * {@code maxInputBytes + 1} bytes are read, so an oversized file is rejected with a typed
     * {@code TOO_LARGE} failure before its content is decoded into a String. The bytes are
     * decoded as strict UTF-8; malformed or truncated sequences fail with a typed
     * {@code MALFORMED_XML} diagnostic instead of a replacement character.
     *
     * @param path the markup file to read
     * @return the parsed document
     * @throws IOException if the file cannot be read
     */
    public MarkupDocument parse(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        String source = MarkupSourceLocation.validateSource(
                path.toAbsolutePath().normalize().toString());
        byte[] utf8;
        try (InputStream in = Files.newInputStream(path)) {
            utf8 = readBounded(in, maxInputBytes);
        }
        if (utf8.length > maxInputBytes) {
            throw diagnostic(
                    MarkupException.Kind.TOO_LARGE,
                    source,
                    "",
                    0,
                    0,
                    "markup input exceeds the " + maxInputBytes + "-byte limit");
        }
        return parseUtf8(utf8.length, decodeUtf8(utf8, source), source);
    }

    /** Shared parse body for in-bounds UTF-8 markup, whether from a String or a file. */
    private MarkupDocument parseUtf8(int byteLength, String xml, String source) {
        RawElement raw = readRaw(xml, source);
        int totalBytes = byteLength;
        Map<String, RawElement> bundles = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : componentBundles.entrySet()) {
            String bundleSource = "bundle:" + entry.getKey();
            requireInputLimit(entry.getValue().length(), bundleSource);
            totalBytes += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            requireInputLimit(totalBytes, bundleSource);
            bundles.put(entry.getKey(), readRaw(entry.getValue(), bundleSource));
        }
        if (!bundles.isEmpty()) {
            raw = ComponentBundles.merge(raw, bundles, maxElements);
        }
        RawElement expanded =
                new ComponentCompiler(maxElements, maxAttributeValue, maxText, extraTags)
                        .expand(raw);
        return new ConcreteElementCompiler(extraTags, maxElements)
                .compile(expanded, totalBytes, source);
    }

    /** Reads bounded raw XML without applying the concrete markup dialect. */
    private RawElement readRaw(String xml, String source) {
        try {
            Handler handler = new Handler(source);
            XMLReader reader = reader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.root();
        } catch (SAXParseException failure) {
            throw diagnostic(
                    MarkupException.Kind.MALFORMED_XML,
                    source,
                    "",
                    failure.getLineNumber(),
                    failure.getColumnNumber(),
                    failure.getMessage());
        } catch (SAXException failure) {
            if (failure.getCause() instanceof MarkupException markup) {
                throw markup;
            }
            throw diagnostic(
                    MarkupException.Kind.MALFORMED_XML,
                    source,
                    "",
                    0,
                    0,
                    failure.getMessage());
        } catch (IOException | ParserConfigurationException impossible) {
            throw new IllegalStateException("SAX parser unavailable", impossible);
        }
    }

    private void requireInputLimit(int byteLength, String source) {
        if (byteLength > maxInputBytes) {
            throw diagnostic(
                    MarkupException.Kind.TOO_LARGE,
                    source,
                    "",
                    0,
                    0,
                    "markup input of " + byteLength + " bytes exceeds the "
                            + maxInputBytes + "-byte limit");
        }
    }

    /**
     * Reads at most {@code maxBytes + 1} bytes from {@code in}, stopping as soon as the
     * limit-plus-one sentinel is reached so an oversized input is never materialized. The
     * returned array holds exactly the bytes read.
     */
    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        int capacity = maxBytes == Integer.MAX_VALUE ? maxBytes : maxBytes + 1;
        byte[] buffer = new byte[Math.min(capacity, 4 * 1024)];
        int total = 0;
        while (total < capacity) {
            if (total == buffer.length) {
                buffer = Arrays.copyOf(buffer, nextBufferLength(buffer.length, capacity));
            }
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
    }

    /**
     * Next length when growing {@code current} toward {@code capacity}: doubles while below
     * half of capacity, then jumps to capacity. Comparing against {@code capacity / 2} before
     * doubling keeps the product strictly below {@code Integer.MAX_VALUE}, so growth never
     * overflows the int range for any configured limit.
     */
    static int nextBufferLength(int current, int capacity) {
        return current < capacity / 2 ? current * 2 : capacity;
    }

    /** Decodes strict UTF-8; malformed or truncated input fails with a typed diagnostic. */
    private static String decodeUtf8(byte[] utf8, String source) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw diagnostic(
                    MarkupException.Kind.MALFORMED_XML,
                    source,
                    "",
                    0,
                    0,
                    "markup input is not valid UTF-8: " + failure.getMessage());
        }
    }

    private static MarkupException diagnostic(
            MarkupException.Kind kind,
            String source,
            String path,
            int line,
            int column,
            String message) {
        String safeMessage = message == null ? kind.name() : message;
        return new MarkupException(
                kind,
                path,
                line,
                column,
                safeMessage,
                new MarkupDiagnosticContext(
                        source,
                        "",
                        "",
                        "",
                        "",
                        "document rejected before Scene2D build",
                        List.of()));
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

    /** SAX handler that builds bounded raw XML nodes without applying the concrete dialect. */
    private final class Handler extends DefaultHandler {
        private final String source;
        private final Deque<Frame> stack = new ArrayDeque<>();
        private final List<RawElement> roots = new ArrayList<>();
        private final ElementPathTracker paths = new ElementPathTracker();
        private Locator locator;
        private RawElement root;
        private int elements;

        private Handler(String source) {
            this.source = source;
        }

        @Override public void setDocumentLocator(Locator newLocator) {
            locator = newLocator;
        }

        @Override public void startElement(String uri, String localName, String qName,
                Attributes attributes) throws SAXException {
            String tag = qName;
            int line = locator == null ? 0 : locator.getLineNumber();
            int column = locator == null ? 0 : locator.getColumnNumber();
            if (tag.length() > MAX_NAME_LENGTH) {
                String parent = paths.current();
                String path = parent.isEmpty() ? "<element>" : parent + "/<element>";
                throw tooLarge(
                        "element name exceeds the " + MAX_NAME_LENGTH + "-character limit",
                        path,
                        line,
                        column);
            }
            String path = paths.enter(tag);
            if (++elements > maxElements) {
                throw tooLarge("document exceeds the " + maxElements + "-element limit", path,
                        line, column);
            }
            if (stack.size() >= maxDepth) {
                throw tooLarge("nesting exceeds the " + maxDepth + "-level limit", path,
                        line, column);
            }
            MarkupSourceLocation origin =
                    new MarkupSourceLocation(source, path, line, column);
            LinkedHashMap<String, RawAttribute> attrs = new LinkedHashMap<>();
            for (int index = 0; index < attributes.getLength(); index++) {
                String name = attributes.getQName(index);
                String value = attributes.getValue(index);
                if (name.length() > MAX_NAME_LENGTH) {
                    throw tooLarge(
                            "attribute name exceeds the " + MAX_NAME_LENGTH
                                    + "-character limit",
                            path,
                            line,
                            column);
                }
                if (value.length() > maxAttributeValue) {
                    throw tooLarge("attribute \"" + name + "\" exceeds the "
                            + maxAttributeValue + "-character limit", path, line, column);
                }
                attrs.put(name, new RawAttribute(value, origin));
            }
            stack.push(new Frame(tag, path, attrs, origin));
        }

        @Override public void characters(char[] ch, int start, int length) throws SAXException {
            Frame frame = stack.peek();
            if (frame == null) {
                return;
            }
            if (!frame.children.isEmpty()) {
                String text = new String(ch, start, length);
                if (!text.isBlank()) {
                    throw diagnostic(
                            MarkupException.Kind.INVALID_VALUE,
                            source,
                            frame.path,
                            line(),
                            column(),
                            "mixed text content is not supported in <" + frame.tag + ">");
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
            paths.exit();
            RawElement element = frame.build();
            if (stack.isEmpty()) {
                roots.add(element);
            } else {
                stack.peek().children.add(element);
            }
        }

        @Override public void endDocument() throws SAXException {
            if (roots.isEmpty()) {
                throw diagnostic(
                        MarkupException.Kind.MALFORMED_XML,
                        source,
                        "",
                        line(),
                        column(),
                        "document contains no root element");
            }
            if (roots.size() > 1) {
                RawElement documentRoot = roots.get(0);
                throw diagnostic(
                        MarkupException.Kind.MALFORMED_XML,
                        source,
                        documentRoot.tag(),
                        documentRoot.origin().line(),
                        documentRoot.origin().column(),
                        "document contains multiple root elements");
            }
            root = roots.get(0);
        }

        RawElement root() {
            return root;
        }

        @Override public void error(SAXParseException failure) throws SAXException {
            throw failure;
        }

        @Override public void fatalError(SAXParseException failure) throws SAXException {
            throw failure;
        }

        private MarkupException tooLarge(String message, String path, int line, int column) {
            return diagnostic(
                    MarkupException.Kind.TOO_LARGE, source, path, line, column, message);
        }

        private int line() {
            return locator == null ? 0 : locator.getLineNumber();
        }

        private int column() {
            return locator == null ? 0 : locator.getColumnNumber();
        }
    }

    /** One in-progress element during parsing. */
    private static final class Frame {
        private final String tag;
        private final String path;
        private final LinkedHashMap<String, RawAttribute> attrs;
        private final List<RawElement> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final MarkupSourceLocation origin;

        private Frame(
                String tag,
                String path,
                LinkedHashMap<String, RawAttribute> attrs,
                MarkupSourceLocation origin) {
            this.tag = tag;
            this.path = path;
            this.attrs = attrs;
            this.origin = origin;
        }

        private RawElement build() {
            String content = text.toString().strip();
            if (!content.isEmpty() && !children.isEmpty()) {
                throw diagnostic(
                        MarkupException.Kind.INVALID_VALUE,
                        origin.source(),
                        path,
                        origin.line(),
                        origin.column(),
                        "mixed text content is not supported in <" + tag + ">");
            }
            return new RawElement(tag, attrs, content, children, origin, List.of());
        }
    }
}
