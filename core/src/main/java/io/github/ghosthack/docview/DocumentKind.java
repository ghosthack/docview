package io.github.ghosthack.docview;

/**
 * A text document family. The kind selects the Reader presentation and the
 * syntax grammar; it never grants write capability.
 */
public enum DocumentKind {
    PLAIN("TXT", "Plain text", false),
    MARKDOWN("MD", "Markdown", true),
    HTML("HTML", "HTML", true),
    JAVA("JAVA", "Java", false),
    XML("XML", "XML", false),
    PROPERTIES("PROP", "Properties", false),
    YAML("YAML", "YAML", false),
    JSON("JSON", "JSON", false),
    RUBY("RB", "Ruby", false),
    TCL("TCL", "Tcl", false),
    SCRIPT("CODE", "Script", false),
    SOURCE("CODE", "Source", false),
    SHELL("SH", "Shell", false),
    SQL("SQL", "SQL", false),
    DATA("DATA", "Structured data", false),
    LOG("LOG", "Log", false);

    private final String badge;
    private final String label;
    private final boolean formattedReader;

    DocumentKind(String badge, String label, boolean formattedReader) {
        this.badge = badge;
        this.label = label;
        this.formattedReader = formattedReader;
    }

    public String badge() {
        return badge;
    }

    public String label() {
        return label;
    }

    public boolean formattedReader() {
        return formattedReader;
    }
}
