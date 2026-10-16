package io.github.ghosthack.docview;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Exact engine language identity plus the application's broader presentation
 * family. For example, Python and JavaScript are distinct IDs even though both
 * currently use {@link DocumentKind#SCRIPT} in the Vanilla engine.
 */
public record HighlightLanguage(String id, DocumentKind kind) {

    public HighlightLanguage {
        id = normalize(id);
        if (id.isEmpty()) id = idForKind(kind);
        Objects.requireNonNull(kind, "kind");
    }

    public static HighlightLanguage forDocument(DocumentContent document) {
        String name = document.path().getFileName() == null
                ? "" : document.path().getFileName().toString()
                        .toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String id = dot > 0 && dot + 1 < name.length()
                ? alias(name.substring(dot + 1))
                : specialName(name, document.kind());
        return new HighlightLanguage(id, document.kind());
    }

    public static HighlightLanguage forMarkdownFence(String fence) {
        String info = fence.length() <= 3 ? "" : fence.substring(3).strip();
        if (info.startsWith("{.") && info.endsWith("}")) {
            info = info.substring(2, info.length() - 1);
        }
        int separator = 0;
        while (separator < info.length()
                && !Character.isWhitespace(info.charAt(separator))) {
            separator++;
        }
        String token = normalize(info.substring(0, separator));
        int dots = 0;
        while (dots < token.length() && token.charAt(dots) == '.') dots++;
        token = token.substring(dots);
        if (token.isEmpty() || token.equals("text")
                || token.equals("plaintext") || token.equals("none")) {
            return new HighlightLanguage("plain", DocumentKind.PLAIN);
        }
        String id = alias(token);
        DocumentKind kind = switch (id) {
            case "ruby" -> DocumentKind.RUBY;
            case "tcl" -> DocumentKind.TCL;
            case "javascript", "typescript", "python", "php",
                    "lua", "perl", "r", "css", "scss", "less" ->
                    DocumentKind.SCRIPT;
            case "shell" -> DocumentKind.SHELL;
            case "cpp", "csharp", "kotlin", "rust" ->
                    DocumentKind.SOURCE;
            default -> DocumentClassifier.classify(
                            Path.of("snippet." + token))
                    .orElse(DocumentKind.SOURCE);
        };
        return new HighlightLanguage(id, kind);
    }

    private static String alias(String id) {
        return switch (normalize(id)) {
            case "js", "mjs", "cjs" -> "javascript";
            case "jsx" -> "javascriptreact";
            case "ts" -> "typescript";
            case "tsx" -> "typescriptreact";
            case "py" -> "python";
            case "rb" -> "ruby";
            case "sh", "bash", "zsh", "fish",
                    "console", "terminal" -> "shell";
            case "yml" -> "yaml";
            case "md", "markdown", "mdown", "mkd" -> "markdown";
            case "htm", "xhtml" -> "html";
            case "c++", "cc", "cxx", "hpp" -> "cpp";
            case "c#", "cs" -> "csharp";
            case "pl" -> "perl";
            default -> normalize(id);
        };
    }

    private static String specialName(
            String name, DocumentKind fallback) {
        return switch (name) {
            case "dockerfile", "containerfile" -> "dockerfile";
            case "makefile", "gnumakefile" -> "makefile";
            default -> idForKind(fallback);
        };
    }

    private static String idForKind(DocumentKind kind) {
        if (kind == null) return "plain";
        return switch (kind) {
            case PLAIN -> "plain";
            case MARKDOWN -> "markdown";
            case HTML -> "html";
            case JAVA -> "java";
            case XML -> "xml";
            case PROPERTIES -> "properties";
            case YAML -> "yaml";
            case JSON -> "json";
            case RUBY -> "ruby";
            case TCL -> "tcl";
            case SCRIPT -> "script";
            case SOURCE -> "source";
            case SHELL -> "shell";
            case SQL -> "sql";
            case DATA -> "data";
            case LOG -> "log";
        };
    }

    private static String normalize(String id) {
        return id == null ? ""
                : id.strip().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9+#.-]", "");
    }
}
