package io.github.ghosthack.docview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Engine registry and failure boundary. Plain and Vanilla are built in; the
 * optional {@code docview-textmate} module adds TextMate via
 * {@link ServiceLoader}.
 *
 * <p>External engines can use Java's service-provider mechanism or
 * {@link #register(HighlightEngine)}. A missing, unavailable, unsupported, or
 * failing engine degrades to Vanilla and ultimately plain text.</p>
 */
public final class HighlightEngines {

    public static final String AUTOMATIC = "automatic";
    /** Id of the TextMate engine provided by the optional docview-textmate module. */
    public static final String TEXTMATE = "textmate";
    public static final String VANILLA = VanillaHighlightEngine.ID;
    public static final String NONE = PlainHighlightEngine.ID;

    private static final Map<String, HighlightEngine> ENGINES =
            new LinkedHashMap<>();

    static {
        register(new PlainHighlightEngine());
        register(new VanillaHighlightEngine());
        // Discover through both this library's loader and the context loader
        // (plugin hosts), and isolate providers so one broken entry cannot
        // hide the others.
        loadProviders(HighlightEngines.class.getClassLoader());
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context != HighlightEngines.class.getClassLoader()) {
            loadProviders(context);
        }
    }

    private static void loadProviders(ClassLoader loader) {
        Iterator<HighlightEngine> providers;
        try {
            providers = ServiceLoader.load(HighlightEngine.class, loader).iterator();
        } catch (ServiceConfigurationError ignored) {
            return;
        }
        while (true) {
            try {
                if (!providers.hasNext()) return;
                HighlightEngine engine = providers.next();
                if (!ENGINES.containsKey(normalizeId(engine.id()))) register(engine);
            } catch (ServiceConfigurationError | RuntimeException | LinkageError ignored) {
                // One broken optional provider must not prevent the rest.
            }
        }
    }

    private HighlightEngines() {
    }

    public static synchronized void register(HighlightEngine engine) {
        Objects.requireNonNull(engine, "engine");
        String id = normalizeId(engine.id());
        if (id.equals(AUTOMATIC)) {
            throw new IllegalArgumentException(
                    "'automatic' is reserved for engine selection");
        }
        ENGINES.put(id, engine);
    }

    public static synchronized List<Choice> choices() {
        var choices = new ArrayList<Choice>();
        choices.add(new Choice(AUTOMATIC, "Automatic"));
        ENGINES.values().stream()
                .filter(HighlightEngine::available)
                .sorted(Comparator.comparing(
                        HighlightEngine::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(engine -> new Choice(
                        normalizeId(engine.id()), engine.displayName()))
                .forEach(choices::add);
        return List.copyOf(choices);
    }

    public static HighlightSession open(
            String preference, DocumentContent document) {
        return open(preference, document,
                HighlightLanguage.forDocument(document));
    }

    public static HighlightSession open(
            String preference, DocumentContent document,
            HighlightLanguage language) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(language, "language");
        HighlightEngine engine = select(preference, language);
        HighlightSession session;
        try {
            session = Objects.requireNonNull(
                    engine.open(document, language), "engine session");
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            session = fallbackSession(engine, document, language);
        }
        return guarded(document, session);
    }

    public static String normalizePreference(String preference) {
        String id = normalizeId(preference);
        return id.isEmpty() ? AUTOMATIC : id;
    }

    public static List<HighlightToken> plainLine(String line) {
        return plainTokens(Objects.requireNonNull(line, "line"));
    }

    static List<HighlightToken> plainTokens(String line) {
        return line.isEmpty()
                ? List.of()
                : List.of(new HighlightToken(
                        0, line.length(), HighlightRole.PLAIN));
    }

    /** Text of one line of {@code document}, without its line terminator. */
    public static String lineText(DocumentContent document, int lineIndex) {
        if (lineIndex < 0 || lineIndex >= document.lineCount()) {
            throw new IndexOutOfBoundsException(
                    "line " + lineIndex + " of " + document.lineCount());
        }
        return document.text().substring(
                document.lineStart(lineIndex),
                document.lineEnd(lineIndex));
    }

    private static synchronized HighlightEngine select(
            String preference, HighlightLanguage language) {
        String id = normalizePreference(preference);
        if (!id.equals(AUTOMATIC)) {
            HighlightEngine requested = ENGINES.get(id);
            if (usable(requested, language)) return requested;
        }
        return ENGINES.values().stream()
                .filter(engine -> !normalizeId(engine.id()).equals(NONE))
                .filter(engine -> usable(engine, language))
                .max(Comparator.comparingInt(
                        HighlightEngine::automaticPriority))
                .orElseGet(() -> ENGINES.get(NONE));
    }

    private static boolean usable(
            HighlightEngine engine, HighlightLanguage language) {
        if (engine == null) return false;
        try {
            return engine.available() && engine.supports(language);
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            return false;
        }
    }

    private static HighlightSession fallbackSession(
            HighlightEngine failed, DocumentContent document,
            HighlightLanguage language) {
        HighlightEngine vanilla;
        synchronized (HighlightEngines.class) {
            vanilla = ENGINES.get(VANILLA);
        }
        if (failed != vanilla && usable(vanilla, language)) {
            try {
                return vanilla.open(document, language);
            } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                // Continue to the unconditional plain fallback.
            }
        }
        return new PlainHighlightEngine().open(document, language);
    }

    private static HighlightSession guarded(
            DocumentContent document, HighlightSession delegate) {
        return new HighlightSession() {
            @Override
            public List<HighlightToken> tokensForLine(int lineIndex) {
                String line = lineText(document, lineIndex);
                try {
                    List<HighlightToken> tokens =
                            delegate.tokensForLine(lineIndex);
                    return validPartition(tokens, line.length())
                            ? List.copyOf(tokens)
                            : plainTokens(line);
                } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                    return plainTokens(line);
                }
            }

            @Override
            public void close() {
                try {
                    delegate.close();
                } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
                    // Session cleanup is best-effort when a document is replaced.
                }
            }
        };
    }

    private static boolean validPartition(
            List<HighlightToken> tokens, int lineLength) {
        if (tokens == null) return false;
        if (lineLength == 0) return tokens.isEmpty();
        int cursor = 0;
        for (HighlightToken token : tokens) {
            if (token == null || token.start() != cursor
                    || token.end() <= token.start()
                    || token.end() > lineLength) {
                return false;
            }
            cursor = token.end();
        }
        return cursor == lineLength;
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    public record Choice(String id, String displayName) {
        public Choice {
            id = normalizePreference(id);
            displayName = displayName == null || displayName.isBlank()
                    ? id : displayName.strip();
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
