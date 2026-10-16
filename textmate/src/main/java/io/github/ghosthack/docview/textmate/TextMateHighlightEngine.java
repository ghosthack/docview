package io.github.ghosthack.docview.textmate;

import io.github.ghosthack.docview.DocumentContent;
import io.github.ghosthack.docview.HighlightEngine;
import io.github.ghosthack.docview.HighlightEngines;
import io.github.ghosthack.docview.HighlightLanguage;
import io.github.ghosthack.docview.HighlightRole;
import io.github.ghosthack.docview.HighlightSession;
import io.github.ghosthack.docview.HighlightToken;
import io.github.ghosthack.docview.textmate.internal.LineDeadline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.github.ghosthack.docview.textmate.internal.tm4e.core.grammar.IGrammar;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.grammar.IStateStack;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.grammar.IToken;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.grammar.ITokenizeLineResult;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.registry.IGrammarSource;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.registry.IRegistryOptions;
import io.github.ghosthack.docview.textmate.internal.tm4e.core.registry.Registry;

/**
 * Stateful TextMate highlighter backed by bundled VS Code grammars.
 *
 * <p>TM4E preserves a rule stack between physical lines. Each session caches
 * that stack and the converted tokens, allowing virtualized rows to be
 * revisited without reparsing. An out-of-order request advances from the
 * nearest already-computed prefix because TextMate state is sequential.</p>
 */
public final class TextMateHighlightEngine implements HighlightEngine {

    public static final String ID = "textmate";

    private static final String RESOURCE_ROOT =
            "/io/github/ghosthack/docview/textmate/grammars/";
    private static final Duration LINE_TIME_LIMIT =
            Duration.ofMillis(50);
    /**
     * Longest line handed to TextMate. A single regex search cannot be
     * interrupted, and some grammar patterns are super-linear on hostile
     * input, so longer lines render plain.
     */
    static final int MAX_LINE_LENGTH = 2_000;
    /**
     * Hard limit for one line: past it the running regex search is
     * interrupted and the line renders plain.
     */
    static final Duration LINE_HARD_LIMIT = Duration.ofMillis(100);
    /** Total tokenizing time per session (code block or document). */
    static final long SESSION_BUDGET_NANOS = 1_000_000_000L;

    private static final Map<String, GrammarResource> GRAMMARS =
            Map.ofEntries(
                    grammar("ruby", "source.ruby",
                            "ruby.tmLanguage.json"),
                    grammar("html", "text.html.basic",
                            "html.tmLanguage.json"),
                    grammar("yaml", "source.yaml",
                            "yaml.tmLanguage.json"),
                    grammar("markdown", "text.html.markdown",
                            "markdown.tmLanguage"),
                    grammar("xml", "text.xml",
                            "xml.tmLanguage.json"),
                    grammar("json", "source.json",
                            "json.tmLanguage.json"),
                    grammar("java", "source.java",
                            "java.tmLanguage.json"),
                    grammar("shell", "source.shell",
                            "shell.tmLanguage.json"),
                    grammar("javascript", "source.js",
                            "javascript.tmLanguage.json"),
                    grammar("typescript", "source.ts",
                            "typescript.tmLanguage.json"),
                    grammar("css", "source.css",
                            "css.tmLanguage.json"),
                    grammar("python", "source.python",
                            "python.tmLanguage.json"),
                    grammar("sql", "source.sql",
                            "sql.tmLanguage.json"));

    private static final Map<String, GrammarResource> SCOPES =
            scopeResources();
    private static final Map<String, IGrammar> COMPILED = new HashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "TextMate";
    }

    @Override
    public boolean supports(HighlightLanguage language) {
        return GRAMMARS.containsKey(language.id());
    }

    @Override
    public int automaticPriority() {
        return 100;
    }

    @Override
    public HighlightSession open(
            DocumentContent document, HighlightLanguage language) {
        Objects.requireNonNull(document, "document");
        GrammarResource resource = GRAMMARS.get(language.id());
        if (resource == null) {
            throw new IllegalArgumentException(
                    "No TextMate grammar for " + language.id());
        }
        return new Session(document, compiled(resource.scopeName()));
    }

    private static synchronized IGrammar compiled(String scopeName) {
        return COMPILED.computeIfAbsent(scopeName, scope -> {
            // TM4E attaches compiled rule IDs to raw grammar objects. Keeping
            // each root grammar in its own registry prevents those IDs from
            // leaking between independently compiled language roots.
            Registry registry =
                    new Registry(new BundledGrammarLocator());
            return Objects.requireNonNull(
                    registry.loadGrammar(scope),
                    "TextMate grammar " + scope);
        });
    }

    private static Map.Entry<String, GrammarResource> grammar(
            String language, String scope, String resource) {
        return Map.entry(language,
                new GrammarResource(scope, resource));
    }

    private static Map<String, GrammarResource> scopeResources() {
        var scopes = new HashMap<String, GrammarResource>();
        GRAMMARS.values().forEach(grammar ->
                scopes.put(grammar.scopeName(), grammar));
        scopes.put("source.js.regexp",
                new GrammarResource("source.js.regexp",
                        "javascript-regexp.tmLanguage"));
        scopes.put("source.regexp.python",
                new GrammarResource("source.regexp.python",
                        "python-regexp.tmLanguage.json"));
        return Map.copyOf(scopes);
    }

    private record GrammarResource(String scopeName, String fileName) {
    }

    private static final class BundledGrammarLocator
            implements IRegistryOptions {

        @Override
        public IGrammarSource getGrammarSource(String scopeName) {
            GrammarResource grammar = SCOPES.get(scopeName);
            if (grammar == null) return null;
            return IGrammarSource.fromResource(
                    TextMateHighlightEngine.class,
                    RESOURCE_ROOT + grammar.fileName());
        }

        @Override
        public Collection<String> getInjections(String scopeName) {
            return List.of();
        }
    }

    private static final class Session implements HighlightSession {

        private final DocumentContent document;
        private final IGrammar grammar;
        private final List<List<HighlightToken>> tokens;
        private IStateStack ruleStack;
        private long spentNanos;

        private Session(DocumentContent document, IGrammar grammar) {
            this.document = document;
            this.grammar = grammar;
            this.tokens = new ArrayList<>(document.lineCount());
        }

        @Override
        public synchronized List<HighlightToken> tokensForLine(
                int lineIndex) {
            HighlightEngines.lineText(document, lineIndex);
            while (tokens.size() <= lineIndex) {
                int nextLine = tokens.size();
                String line =
                        HighlightEngines.lineText(document, nextLine);
                if (line.length() > MAX_LINE_LENGTH
                        || spentNanos >= SESSION_BUDGET_NANOS) {
                    // The skipped line may have closed a comment or string;
                    // restart from the initial state rather than carry a
                    // possibly stale one into the following lines.
                    tokens.add(HighlightEngines.plainLine(line));
                    ruleStack = null;
                    continue;
                }
                ITokenizeLineResult<IToken[]> result;
                synchronized (grammar) {
                    long started = System.nanoTime();
                    try {
                        IStateStack stack = ruleStack;
                        result = LineDeadline.within(LINE_HARD_LIMIT,
                                () -> grammar.tokenizeLine(line, stack, LINE_TIME_LIMIT));
                    } catch (LineDeadline.Expired expired) {
                        result = null;
                    } finally {
                        spentNanos += System.nanoTime() - started;
                    }
                }
                if (result == null) {
                    tokens.add(HighlightEngines.plainLine(line));
                    ruleStack = null;
                    continue;
                }
                ruleStack = result.getRuleStack();
                tokens.add(convert(line, result.getTokens()));
            }
            return tokens.get(lineIndex);
        }
    }

    private static List<HighlightToken> convert(
            String line, IToken[] textMateTokens) {
        if (line.isEmpty()) return List.of();
        var converted = new ArrayList<HighlightToken>();
        int cursor = 0;
        if (textMateTokens != null) {
            for (IToken token : textMateTokens) {
                if (token == null) continue;
                int start = Math.max(cursor,
                        Math.min(line.length(), token.getStartIndex()));
                int end = Math.max(start,
                        Math.min(line.length(), token.getEndIndex()));
                if (start > cursor) {
                    append(converted, cursor, start,
                            HighlightRole.PLAIN);
                }
                if (end > start) {
                    append(converted, start, end,
                            roleFor(token.getScopes()));
                    cursor = end;
                }
            }
        }
        if (cursor < line.length()) {
            append(converted, cursor, line.length(),
                    HighlightRole.PLAIN);
        }
        return List.copyOf(converted);
    }

    private static void append(
            List<HighlightToken> tokens, int start, int end,
            HighlightRole role) {
        if (end <= start) return;
        if (!tokens.isEmpty()) {
            HighlightToken previous = tokens.getLast();
            if (previous.end() == start && previous.role() == role) {
                tokens.set(tokens.size() - 1,
                        new HighlightToken(
                                previous.start(), end, role));
                return;
            }
        }
        tokens.add(new HighlightToken(start, end, role));
    }

    static HighlightRole roleFor(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return HighlightRole.PLAIN;
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String scope = scopes.get(i).toLowerCase(Locale.ROOT);
            if (scope.contains("comment")) {
                return HighlightRole.COMMENT;
            }
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String scope = scopes.get(i).toLowerCase(Locale.ROOT);
            if (scope.startsWith("markup.heading")
                    || scope.contains(".heading.")) {
                return HighlightRole.HEADING;
            }
            if (scope.startsWith("markup.underline.link")
                    || scope.startsWith("string.other.link")
                    || scope.contains(".link.")) {
                return HighlightRole.LINK;
            }
            if (scope.startsWith("markup.bold")
                    || scope.startsWith("markup.italic")
                    || scope.startsWith("markup.strikethrough")
                    || scope.contains(".emphasis.")) {
                return HighlightRole.EMPHASIS;
            }
            if (scope.startsWith("string")
                    || scope.contains(".string.")
                    || scope.startsWith("constant.other.symbol")) {
                return HighlightRole.STRING;
            }
            if (scope.startsWith("constant.numeric")
                    || scope.contains(".numeric.")) {
                return HighlightRole.NUMBER;
            }
            if (scope.startsWith("entity.name.tag")
                    && scope.contains(".yaml")) {
                return HighlightRole.KEY;
            }
            if (scope.startsWith("entity.name.tag")
                    || scope.startsWith("punctuation.definition.tag")
                    || scope.startsWith("meta.tag")) {
                return HighlightRole.TAG;
            }
            if (scope.startsWith("entity.other.attribute-name")
                    || scope.contains(".key.")
                    || scope.startsWith("support.type.property-name")) {
                return HighlightRole.KEY;
            }
            if (scope.startsWith("entity.name.type")
                    || scope.startsWith("entity.name.class")
                    || scope.startsWith("entity.name.interface")
                    || scope.startsWith("entity.name.enum")
                    || scope.startsWith("support.type")
                    || scope.startsWith("support.class")
                    || scope.startsWith("storage.type")) {
                return HighlightRole.TYPE;
            }
            if (scope.startsWith("keyword")
                    || scope.startsWith("storage.modifier")
                    || scope.startsWith("variable.language")
                    || scope.startsWith("constant.language")) {
                return HighlightRole.KEYWORD;
            }
        }
        return HighlightRole.PLAIN;
    }
}
