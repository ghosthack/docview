package io.github.ghosthack.docview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight deterministic syntax tokenization for the read-only inspector.
 * It is intentionally presentation-only: malformed input is still shown
 * verbatim and no parser ever changes document text.
 */
final class SyntaxHighlighter {

    public enum Style {
        PLAIN, COMMENT, STRING, NUMBER, KEYWORD, TYPE, KEY, TAG, HEADING, EMPHASIS, LINK
    }

    public record Segment(String text, Style style) {
    }

    /** Shared Source/Reader palette for one token style. */
    public static String color(Style style) {
        return HighlightTheme.color(
                HighlightRole.valueOf(style.name()));
    }

    /** Stable CSS suffix used by formatted Reader code-block spans. */
    public static String styleClass(Style style) {
        return HighlightRole.valueOf(style.name()).styleClass();
    }

    private static final String NUMBER_LITERAL =
            "(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|"
                    // Atomic: a shorter mantissa is always followed by a
                    // digit, '_' or '.', which the trailing lookahead rejects,
                    // so backtracking into it only costs time.
                    + "\\d(?>[\\d_]*\\.?[\\d_]*)?"
                    + "(?:[eE][+-]?\\d+)?[fFdDlL]?)";
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\w.])" + NUMBER_LITERAL + "(?![\\w.])");
    private static final Pattern QUOTED = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern PROPERTY_COMMENT =
            Pattern.compile("^\\s*[#!].*");
    private static final Pattern HASH_COMMENT = Pattern.compile("#.*");
    private static final Pattern SLASH_COMMENT = Pattern.compile("//.*");
    private static final Pattern SCRIPT_COMMENT =
            Pattern.compile("//.*|#.*");
    private static final Pattern SQL_COMMENT = Pattern.compile("--.*");
    private static final Pattern RUBY_REGEX = Pattern.compile(
            "(?<![\\w)])/(?:\\\\.|[^/\\\\\\n])+/[a-z]*");
    private static final Pattern RUBY_PERCENT_LITERAL = Pattern.compile(
            "%[wi](?:\\[[^]]*]|\\([^)]*\\)|\\{[^}]*}|<[^>]*>)");
    private static final Pattern TCL_COMMENT = Pattern.compile(
            "(?:^|(?<=[;{]))[ \\t]*#.*");
    private static final Pattern TCL_QUOTED = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern TCL_BRACED_WORD = Pattern.compile(
            "\\{[^{}\\s\\\\]+}");
    private static final Pattern TCL_VARIABLE = Pattern.compile(
            "\\$(?:\\{[^}\\n]+}|[A-Za-z_][A-Za-z0-9_:]*"
                    + "(?:\\([^\\n)]*\\))?)");
    private static final Pattern XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final Pattern XML_TAG_PREFIX =
            Pattern.compile("(?:</?|<!|<\\?)[A-Za-z_][A-Za-z0-9_.:-]*");
    // Only a tag end while a '<' earlier on the line is still open; see
    // insideTag, which replaces a variable-length lookbehind that rescanned
    // the line behind every candidate position.
    private static final Pattern XML_TAG_END = Pattern.compile("/?>|\\?>");
    private static final Pattern XML_ATTRIBUTE = Pattern.compile(
            "(?<![A-Za-z0-9_.:-])[A-Za-z_:][A-Za-z0-9_.:-]*(?=\\s*=)");
    private static final Pattern XML_UNQUOTED_VALUE =
            Pattern.compile("(?<==)[^\\s>]+");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^\\s*[^#!\\s][^:=\\n]*?(?=\\s*[:=])");
    private static final Pattern YAML_KEY = Pattern.compile("^\\s*[A-Za-z0-9_.-]+(?=\\s*:)");
    private static final Pattern JSON_KEY = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)");
    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(?:#{1,6}\\s+.*|[-=*]{3,}\\s*)$");
    private static final Pattern MD_LINK = Pattern.compile("!?\\[[^]]*+]\\([^)]*+\\)|<https?://[^>]++>");
    private static final Pattern MD_EMPHASIS = Pattern.compile(
            "`[^`]+`|\\*\\*[^*]+\\*\\*|__[^_]+__|(?<!\\*)\\*[^*]+\\*(?!\\*)|(?<!_)_[^_]+_(?!_)");

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "record", "return", "sealed",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
            "yield", "permits", "non-sealed", "true", "false", "null");

    private static final Set<String> GENERAL_KEYWORDS = Set.of(
            "if", "else", "elif", "then", "fi", "for", "while", "do", "done", "case",
            "when", "match", "switch", "return", "break", "continue", "function", "def",
            "class", "struct", "enum", "interface", "trait", "impl", "let", "const",
            "var", "val", "new", "import", "from", "export", "package", "module", "use",
            "public", "private", "protected", "static", "async", "await", "try", "catch",
            "finally", "throw", "throws", "in", "is", "as", "with", "lambda", "fn",
            "true", "false", "null", "nil", "none", "self", "this");

    private static final Set<String> RUBY_KEYWORDS = Set.of(
            "alias", "and", "begin", "break", "case", "class", "def",
            "defined", "do", "else", "elsif", "end", "ensure", "false",
            "for", "if", "in", "module", "next", "nil", "not", "or",
            "redo", "rescue", "retry", "return", "self", "super", "then",
            "true", "undef", "unless", "until", "when", "while", "yield");

    private static final Set<String> TCL_KEYWORDS = Set.of(
            "after", "append", "apply", "array", "break", "catch", "cd",
            "class", "close", "constructor", "continue", "coroutine", "dict",
            "else", "elseif", "eof", "error", "eval", "exec", "exit", "expr",
            "file", "flush", "for", "foreach", "format", "gets", "glob",
            "global", "if", "incr", "info", "interp", "lappend", "lassign",
            "lindex", "linsert", "list", "llength", "lmap", "load", "lrange",
            "lreplace", "lsearch", "lset", "lsort", "method", "my", "namespace",
            "next", "open", "package", "proc", "puts", "pwd", "read", "regexp",
            "regsub", "rename", "return", "scan", "seek", "set", "socket",
            "source", "split", "string", "subst", "switch", "tailcall", "tell",
            "then", "throw", "trace", "try", "unset", "update", "uplevel",
            "upvar", "variable", "vwait", "while", "yield");

    private static final int LONGEST_KEYWORD = java.util.stream.Stream.of(
                    JAVA_KEYWORDS, GENERAL_KEYWORDS, RUBY_KEYWORDS, TCL_KEYWORDS)
            .flatMap(Set::stream).mapToInt(String::length).max().orElse(0);

    /**
     * Longest line the tokenizer colors; longer lines render plain, matching
     * the TextMate engine's cap and bounding the regex work for one line.
     */
    static final int MAX_LINE_LENGTH = 2_000;

    private SyntaxHighlighter() {
    }

    /**
     * Resolves the syntax family for every physical document line. Markdown
     * fence delimiters retain Markdown styling while their contents use the
     * declared language (or plain text for an untyped fence).
     */
    public static List<DocumentKind> syntaxKinds(DocumentContent document) {
        if (document.kind() != DocumentKind.MARKDOWN) {
            return Collections.nCopies(
                    document.lineCount(), document.kind());
        }
        var kinds = new ArrayList<DocumentKind>(document.lineCount());
        boolean inFence = false;
        DocumentKind fencedKind = DocumentKind.PLAIN;
        for (int line = 0; line < document.lineCount(); line++) {
            String text = document.text().substring(
                    document.lineStart(line), document.lineEnd(line));
            String trimmed = text.stripLeading();
            if (trimmed.startsWith("```")) {
                kinds.add(DocumentKind.MARKDOWN);
                if (!inFence) fencedKind = markdownFenceKind(trimmed);
                inFence = !inFence;
            } else {
                kinds.add(inFence ? fencedKind : DocumentKind.MARKDOWN);
            }
        }
        return List.copyOf(kinds);
    }

    /** Maps a Markdown fence's info string onto a Source highlighter family. */
    public static DocumentKind markdownFenceKind(String fence) {
        return DocumentClassifier.classifyMarkdownFence(fence);
    }

    public static List<Segment> highlightLine(String line, DocumentKind kind, boolean enabled) {
        if (!enabled || line.isEmpty() || line.length() > MAX_LINE_LENGTH) {
            return List.of(new Segment(line, Style.PLAIN));
        }
        List<Rule> rules = rulesFor(kind);
        var scanners = new ArrayList<RuleScanner>(rules.size());
        for (Rule rule : rules) scanners.add(new RuleScanner(rule, line));
        // Prose/plain formats must not inherit the programming keyword
        // table: sentence-openers such as "For" or "Use" are not code.
        Keywords keywords = usesKeywordRules(kind)
                ? new Keywords(line, kind) : null;
        var result = new ArrayList<Segment>();
        int cursor = 0;
        while (cursor < line.length()) {
            Match best = null;
            for (RuleScanner scanner : scanners) {
                Match match = scanner.next(cursor);
                if (match != null && (best == null || match.start < best.start
                        || (match.start == best.start && match.priority < best.priority))) {
                    best = match;
                }
            }
            Match keyword = keywords != null ? keywords.next(cursor) : null;
            if (keyword != null && (best == null || keyword.start < best.start
                    || (keyword.start == best.start && keyword.priority < best.priority))) {
                best = keyword;
            }
            if (best == null) {
                append(result, line.substring(cursor), Style.PLAIN);
                break;
            }
            if (best.start > cursor) {
                append(result, line.substring(cursor, best.start), Style.PLAIN);
            }
            append(result, line.substring(best.start, best.end), best.style);
            cursor = Math.max(best.end, cursor + 1);
        }
        return List.copyOf(result);
    }

    private static List<Rule> rulesFor(DocumentKind kind) {
        return switch (kind) {
            case XML, HTML -> List.of(
                    new Rule(XML_COMMENT, Style.COMMENT, 0),
                    // Tokenize tag structure separately so attributes do not
                    // inherit the element-name color.
                    new Rule(XML_TAG_PREFIX, Style.TAG, 1),
                    new Rule(XML_TAG_END, Style.TAG, 2),
                    new Rule(XML_ATTRIBUTE, Style.KEY, 3),
                    new Rule(QUOTED, Style.STRING, 4),
                    new Rule(XML_UNQUOTED_VALUE, Style.STRING, 5));
            case PROPERTIES -> List.of(
                    new Rule(PROPERTY_COMMENT, Style.COMMENT, 0),
                    new Rule(PROPERTY_KEY, Style.KEY, 1),
                    new Rule(QUOTED, Style.STRING, 2),
                    new Rule(NUMBER, Style.NUMBER, 3));
            case YAML -> List.of(
                    new Rule(HASH_COMMENT, Style.COMMENT, 0),
                    new Rule(YAML_KEY, Style.KEY, 1),
                    new Rule(QUOTED, Style.STRING, 2));
            case JSON -> List.of(
                    new Rule(JSON_KEY, Style.KEY, 0),
                    new Rule(QUOTED, Style.STRING, 1),
                    new Rule(NUMBER, Style.NUMBER, 2));
            case MARKDOWN -> List.of(
                    new Rule(MD_HEADING, Style.HEADING, 0),
                    new Rule(MD_LINK, Style.LINK, 1),
                    new Rule(MD_EMPHASIS, Style.EMPHASIS, 2));
            case RUBY -> List.of(
                    new Rule(HASH_COMMENT, Style.COMMENT, 0),
                    new Rule(QUOTED, Style.STRING, 1),
                    new Rule(RUBY_REGEX, Style.STRING, 1),
                    new Rule(RUBY_PERCENT_LITERAL, Style.STRING, 1),
                    new Rule(NUMBER, Style.NUMBER, 2));
            case TCL -> List.of(
                    new Rule(TCL_COMMENT, Style.COMMENT, 0),
                    new Rule(TCL_QUOTED, Style.STRING, 1),
                    new Rule(TCL_BRACED_WORD, Style.STRING, 1),
                    new Rule(TCL_VARIABLE, Style.KEY, 2),
                    new Rule(NUMBER, Style.NUMBER, 3));
            case JAVA, SOURCE -> List.of(
                    new Rule(SLASH_COMMENT, Style.COMMENT, 0),
                    new Rule(QUOTED, Style.STRING, 1),
                    new Rule(NUMBER, Style.NUMBER, 2));
            case SCRIPT -> List.of(
                    new Rule(SCRIPT_COMMENT, Style.COMMENT, 0),
                    new Rule(QUOTED, Style.STRING, 1),
                    new Rule(NUMBER, Style.NUMBER, 2));
            case SHELL -> List.of(
                    new Rule(HASH_COMMENT, Style.COMMENT, 0),
                    new Rule(QUOTED, Style.STRING, 1),
                    new Rule(NUMBER, Style.NUMBER, 2));
            case SQL -> List.of(
                    new Rule(SQL_COMMENT, Style.COMMENT, 0),
                    new Rule(QUOTED, Style.STRING, 1),
                    new Rule(NUMBER, Style.NUMBER, 2));
            default -> List.of(new Rule(NUMBER, Style.NUMBER, 2));
        };
    }

    private static Set<String> keywordsFor(DocumentKind kind) {
        return switch (kind) {
            case JAVA -> JAVA_KEYWORDS;
            case RUBY -> RUBY_KEYWORDS;
            case TCL -> TCL_KEYWORDS;
            default -> GENERAL_KEYWORDS;
        };
    }

    /**
     * Marks the positions where a tag end may start: some '<' earlier on the
     * line has not yet been closed by a '>'.
     */
    private static boolean[] insideTag(String line) {
        boolean[] inside = new boolean[line.length()];
        boolean open = false;
        for (int i = 0; i < line.length(); i++) {
            inside[i] = open;
            char c = line.charAt(i);
            if (c == '<') open = true;
            else if (c == '>') open = false;
        }
        return inside;
    }

    private static boolean usesKeywordRules(DocumentKind kind) {
        return switch (kind) {
            case JAVA, RUBY, TCL, SCRIPT, SOURCE, SHELL, SQL, JSON -> true;
            default -> false;
        };
    }

    private static void append(List<Segment> result, String text, Style style) {
        if (text.isEmpty()) return;
        if (!result.isEmpty() && result.getLast().style == style) {
            Segment previous = result.removeLast();
            result.add(new Segment(previous.text + text, style));
        } else {
            result.add(new Segment(text, style));
        }
    }

    private record Rule(Pattern pattern, Style style, int priority) {
    }

    /**
     * One rule's next match on a line. The cursor only moves forward and a
     * match position does not depend on where the search began, so the cached
     * match stays valid until the cursor passes its start; each rule therefore
     * scans the line once instead of once per token.
     */
    private static final class RuleScanner {
        private final Rule rule;
        private final Matcher matcher;
        private final boolean[] insideTag;
        private Match next;
        private boolean exhausted;

        RuleScanner(Rule rule, String line) {
            this.rule = rule;
            this.matcher = rule.pattern.matcher(line);
            this.insideTag = rule.pattern == XML_TAG_END ? insideTag(line) : null;
        }

        Match next(int cursor) {
            if (exhausted) return null;
            if (next != null && next.start >= cursor) return next;
            int from = cursor;
            while (matcher.find(from)) {
                if (insideTag == null || insideTag[matcher.start()]) {
                    next = new Match(matcher.start(), matcher.end(),
                            rule.style, rule.priority);
                    return next;
                }
                from = matcher.start() + 1;
            }
            exhausted = true;
            next = null;
            return null;
        }
    }

    /**
     * Keyword lookup for one line. The scan from any cursor skips to the next
     * identifier start and reads the identifier through the following
     * identifier-part characters; both tables are filled right to left, so
     * the next keyword from any cursor is a constant-time lookup.
     */
    private static final class Keywords {
        /** Index just past the identifier-part run that follows each index. */
        private final int[] wordEnd;
        /** Start of the first keyword the scan from each index reaches, or -1. */
        private final int[] next;

        Keywords(String line, DocumentKind kind) {
            Set<String> words = keywordsFor(kind);
            int length = line.length();
            wordEnd = new int[length];
            next = new int[length + 1];
            next[length] = -1;
            for (int i = length - 1; i >= 0; i--) {
                wordEnd[i] = i + 1 < length
                        && Character.isJavaIdentifierPart(line.charAt(i + 1))
                        ? wordEnd[i + 1] : i + 1;
                if (!Character.isJavaIdentifierStart(line.charAt(i))) {
                    next[i] = next[i + 1];
                } else {
                    // A non-keyword identifier does not end the search: the
                    // keyword may be a later word on the line.
                    next[i] = isKeyword(line, i, wordEnd[i], words)
                            ? i : next[wordEnd[i]];
                }
            }
        }

        Match next(int cursor) {
            int start = next[cursor];
            return start < 0 ? null
                    : new Match(start, wordEnd[start], Style.KEYWORD, 4);
        }

        private static boolean isKeyword(
                String line, int start, int end, Set<String> words) {
            // Lower-casing never shortens a word, so longer ones cannot match.
            return end - start <= LONGEST_KEYWORD && words.contains(
                    line.substring(start, end).toLowerCase(java.util.Locale.ROOT));
        }
    }

    private record Match(int start, int end, Style style, int priority) {
    }
}
