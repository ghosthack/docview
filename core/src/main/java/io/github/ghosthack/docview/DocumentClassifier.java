package io.github.ghosthack.docview;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fast, read-free document classification. Unknown and known binary/media
 * names are deliberately declined: only formats that can be inspected as text
 * enter the document ring.
 */
public final class DocumentClassifier {

    private static final Map<String, DocumentKind> EXTENSIONS = Map.ofEntries(
            Map.entry("txt", DocumentKind.PLAIN),
            Map.entry("text", DocumentKind.PLAIN),
            Map.entry("readme", DocumentKind.PLAIN),
            Map.entry("md", DocumentKind.MARKDOWN),
            Map.entry("markdown", DocumentKind.MARKDOWN),
            Map.entry("mdown", DocumentKind.MARKDOWN),
            Map.entry("mkd", DocumentKind.MARKDOWN),
            Map.entry("rst", DocumentKind.MARKDOWN),
            Map.entry("adoc", DocumentKind.MARKDOWN),
            Map.entry("asciidoc", DocumentKind.MARKDOWN),
            Map.entry("html", DocumentKind.HTML),
            Map.entry("htm", DocumentKind.HTML),
            Map.entry("xhtml", DocumentKind.HTML),
            Map.entry("svg", DocumentKind.XML),
            Map.entry("java", DocumentKind.JAVA),
            Map.entry("kt", DocumentKind.SOURCE),
            Map.entry("kts", DocumentKind.SOURCE),
            Map.entry("scala", DocumentKind.SOURCE),
            Map.entry("groovy", DocumentKind.SOURCE),
            Map.entry("gradle", DocumentKind.SOURCE),
            Map.entry("xml", DocumentKind.XML),
            Map.entry("fxml", DocumentKind.XML),
            Map.entry("xsd", DocumentKind.XML),
            Map.entry("xsl", DocumentKind.XML),
            Map.entry("xslt", DocumentKind.XML),
            Map.entry("plist", DocumentKind.XML),
            Map.entry("properties", DocumentKind.PROPERTIES),
            Map.entry("ini", DocumentKind.PROPERTIES),
            Map.entry("cfg", DocumentKind.PROPERTIES),
            Map.entry("conf", DocumentKind.PROPERTIES),
            Map.entry("toml", DocumentKind.PROPERTIES),
            Map.entry("env", DocumentKind.PROPERTIES),
            Map.entry("yaml", DocumentKind.YAML),
            Map.entry("yml", DocumentKind.YAML),
            Map.entry("json", DocumentKind.JSON),
            Map.entry("jsonl", DocumentKind.JSON),
            Map.entry("geojson", DocumentKind.JSON),
            Map.entry("js", DocumentKind.SCRIPT),
            Map.entry("mjs", DocumentKind.SCRIPT),
            Map.entry("cjs", DocumentKind.SCRIPT),
            Map.entry("jsx", DocumentKind.SCRIPT),
            Map.entry("ts", DocumentKind.SCRIPT),
            Map.entry("tsx", DocumentKind.SCRIPT),
            Map.entry("css", DocumentKind.SCRIPT),
            Map.entry("scss", DocumentKind.SCRIPT),
            Map.entry("less", DocumentKind.SCRIPT),
            Map.entry("py", DocumentKind.SCRIPT),
            Map.entry("rb", DocumentKind.RUBY),
            Map.entry("tcl", DocumentKind.TCL),
            Map.entry("php", DocumentKind.SCRIPT),
            Map.entry("lua", DocumentKind.SCRIPT),
            Map.entry("pl", DocumentKind.SCRIPT),
            Map.entry("r", DocumentKind.SCRIPT),
            Map.entry("c", DocumentKind.SOURCE),
            Map.entry("h", DocumentKind.SOURCE),
            Map.entry("cc", DocumentKind.SOURCE),
            Map.entry("cpp", DocumentKind.SOURCE),
            Map.entry("cxx", DocumentKind.SOURCE),
            Map.entry("hpp", DocumentKind.SOURCE),
            Map.entry("cs", DocumentKind.SOURCE),
            Map.entry("go", DocumentKind.SOURCE),
            Map.entry("rs", DocumentKind.SOURCE),
            Map.entry("swift", DocumentKind.SOURCE),
            Map.entry("dart", DocumentKind.SOURCE),
            Map.entry("sh", DocumentKind.SHELL),
            Map.entry("bash", DocumentKind.SHELL),
            Map.entry("zsh", DocumentKind.SHELL),
            Map.entry("fish", DocumentKind.SHELL),
            Map.entry("ps1", DocumentKind.SHELL),
            Map.entry("bat", DocumentKind.SHELL),
            Map.entry("cmd", DocumentKind.SHELL),
            Map.entry("sql", DocumentKind.SQL),
            Map.entry("csv", DocumentKind.DATA),
            Map.entry("tsv", DocumentKind.DATA),
            Map.entry("tex", DocumentKind.PLAIN),
            Map.entry("log", DocumentKind.LOG),
            Map.entry("diff", DocumentKind.SOURCE),
            Map.entry("patch", DocumentKind.SOURCE)
    );

    private static final Set<String> TEXT_NAMES = Set.of(
            "readme", "license", "copying", "notice", "authors", "contributors",
            "changelog", "changes", "news", "todo", "makefile", "dockerfile",
            "jenkinsfile", "procfile", "gemfile", "rakefile", "gradle.properties",
            "mvnw", "gradlew", ".gitignore", ".gitattributes", ".gitmodules",
            ".editorconfig", ".env", ".bashrc", ".bash_profile", ".zshrc",
            ".zprofile", ".profile", ".npmrc", ".yarnrc", ".curlrc");

    private DocumentClassifier() {
    }

    /**
     * Maps a Markdown fence info string onto the application's exact document
     * language identity, independently of any highlighting engine.
     */
    public static DocumentKind classifyMarkdownFence(String fence) {
        return HighlightLanguage.forMarkdownFence(fence).kind();
    }

    public static Optional<DocumentKind> classify(Path file) {
        Path namePath = file.getFileName();
        if (namePath == null) return Optional.empty();
        String name = namePath.toString().toLowerCase(Locale.ROOT);
        if (TEXT_NAMES.contains(name)) return Optional.of(kindForSpecialName(name));
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            return Optional.ofNullable(EXTENSIONS.get(name.substring(dot + 1)));
        }
        return Optional.empty();
    }

    public static boolean isDocument(Path file) {
        return classify(file).isPresent();
    }

    private static DocumentKind kindForSpecialName(String name) {
        if (name.equals("gradle.properties") || name.equals(".env")
                || name.endsWith("rc") || name.equals(".editorconfig")) {
            return DocumentKind.PROPERTIES;
        }
        if (name.equals("makefile") || name.equals("dockerfile")
                || name.equals("jenkinsfile") || name.equals("procfile")
                || name.equals("gemfile") || name.equals("rakefile")
                || name.equals("mvnw") || name.equals("gradlew")) {
            return DocumentKind.SCRIPT;
        }
        return DocumentKind.PLAIN;
    }
}
