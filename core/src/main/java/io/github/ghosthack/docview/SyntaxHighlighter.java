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
                    + "\\d(?:[\\d_]*\\.?[\\d_]*)?"
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
    private static final Pattern XML_TAG_END =
            Pattern.compile(
                    "(?<=<[^>]{0,8192})(?:/?>|\\?>)");
    private static final Pattern XML_ATTRIBUTE = Pattern.compile(
            "(?<![A-Za-z0-9_.:-])[A-Za-z_:][A-Za-z0-9_.:-]*(?=\\s*=)");
    private static final Pattern XML_UNQUOTED_VALUE =
            Pattern.compile("(?<==)[^\\s>]+");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^\\s*[^#!\\s][^:=\\n]*?(?=\\s*[:=])");
    private static final Pattern YAML_KEY = Pattern.compile("^\\s*[A-Za-z0-9_.-]+(?=\\s*:)");
    private static final Pattern JSON_KEY = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)");
    private static final Pattern MD_HEADING = Pattern.compile("^\\s{0,3}(?:#{1,6}\\s+.*|[-=*]{3,}\\s*)$");
    private static final Pattern MD_LINK = Pattern.compile("!?\\[[^]]*]\\([^)]*\\)|<https?://[^>]+>");
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
        if (!enabled || line.isEmpty()) return List.of(new Segment(line, Style.PLAIN));
        List<Rule> rules = rulesFor(kind);
        var result = new ArrayList<Segment>();
        int cursor = 0;
        while (cursor < line.length()) {
            Match best = null;
            for (Rule rule : rules) {
                Matcher matcher = rule.pattern.matcher(line);
                if (!matcher.find(cursor)) continue;
                int start = matcher.start();
                int end = matcher.end();
                if (best == null || start < best.start
                        || (start == best.start && rule.priority < best.priority)) {
                    best = new Match(start, end, rule.style, rule.priority);
                }
            }
            // Prose/plain formats must not inherit the programming keyword
            // table: sentence-openers such as "For" or "Use" are not code.
            Match keyword = usesKeywordRules(kind)
                    ? keywordAt(line, cursor, kind) : null;
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

    private static Match keywordAt(String line, int from, DocumentKind kind) {
        Set<String> words = switch (kind) {
            case JAVA -> JAVA_KEYWORDS;
            case RUBY -> RUBY_KEYWORDS;
            case TCL -> TCL_KEYWORDS;
            default -> GENERAL_KEYWORDS;
        };
        int i = from;
        while (i < line.length() && !Character.isJavaIdentifierStart(line.charAt(i))) i++;
        if (i >= line.length()) return null;
        int end = i + 1;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
        String word = line.substring(i, end);
        if (!words.contains(word.toLowerCase(java.util.Locale.ROOT))) return null;
        return new Match(i, end, Style.KEYWORD, 4);
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

    private record Match(int start, int end, Style style, int priority) {
    }
}
