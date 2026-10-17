package io.github.ghosthack.docview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeMarkupRendererTest {

    private static final Duration FAST = Duration.ofSeconds(5);

    private static final ReaderStyle STYLE =
            new ReaderStyle(
                    "Reader Serif", "Menlo", 15, false, false,
                    1.35, 0, true, 4);

    @Test
    void xmlReaderBuildsAnExpandableElementAndPropertyTree() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.XML, """
                <catalog version="1 &amp; 2">
                  <book id="b1"><title>First &amp; best</title><price>12.50</price></book>
                  <empty/>
                </catalog>
                """), STYLE);

        assertTrue(page.contains("<span>Node tag</span><span>Node value</span>"));
        assertTrue(page.contains(
                "id=\"xml-condense-all\" type=\"checkbox\" checked"));
        assertTrue(page.contains("aria-label=\"Contract leaf nodes globally\""));
        assertTrue(page.contains("<span class=\"xml-global-contract-text\">"
                + "Contract leaves</span>"));
        assertTrue(page.contains("<span class=\"xml-global-restore-text\">"
                + "Restore leaves</span>"));
        assertTrue(page.contains(
                "id=\"xml-stack-pills\" type=\"checkbox\" checked"));
        assertTrue(page.contains("aria-label=\"Use vertical key-value pills\""));
        assertTrue(page.contains("<span class=\"xml-pill-stack-text\">"
                + "Vertical pills</span>"));
        assertTrue(page.contains("<span class=\"xml-pill-inline-text\">"
                + "Horizontal pills</span>"));
        assertTrue(page.contains("<body class=\"xml-reader-page\">"));
        assertTrue(page.contains(
                "body.xml-reader-page { padding-left:2em; padding-right:2em; }"));
        assertTrue(page.contains(
                ".xml-heading { position:sticky; top:0; z-index:20;"));
        assertTrue(page.contains(
                "-webkit-transform:translate3d(0, 0, 0);\n"
                + "  transform:translate3d(0, 0, 0);"));
        assertTrue(page.contains(
                "backface-visibility:hidden; will-change:transform;\n"
                + "  isolation:isolate;"));
        assertTrue(page.contains(
                ".xml-reader { width:100%;\n"
                + "  min-width:var(--xml-reader-min-width, 45.2em);"));
        assertTrue(page.contains(
                "grid-template-columns:\n"
                + "    minmax(var(--xml-tag-min-width, 16em), .5fr)\n"
                + "    minmax(0, 1.5fr);"));
        assertFalse(page.contains(".xml-reader { max-width:"));
        assertTrue(page.contains(
                "<section class=\"xml-reader\""
                + " style=\"--xml-tag-min-width:16.00em;"
                + "--xml-reader-min-width:45.20em\""));
        assertTrue(page.contains("<ul class=\"xml-tree\" role=\"tree\">"));
        assertTrue(page.contains("<li class=\"xml-node xml-root xml-expandable\""
                + " style=\"--xml-indent:0.0em\" role=\"treeitem\">"
                + "<details open><summary class=\"xml-row\">"));
        assertTrue(page.contains("<span class=\"xml-tag\">catalog</span>"));
        assertTrue(page.contains("<span class=\"xml-tag\">catalog</span>"
                + "<label class=\"xml-condense-label\""
                + " for=\"xml-condense-1\""));
        assertTrue(page.contains("<span class=\"xml-tag\">title</span>"));
        assertTrue(page.contains(
                "<details><summary class=\"xml-row xml-nested-row\">"
                + "<span class=\"xml-tag-cell\"><span class=\"xml-tag\">book</span>"));
        assertFalse(page.contains("class=\"xml-bracket\""));
        assertTrue(page.contains("<span class=\"xml-value\">"
                + "<span class=\"xml-normal-value\">First &amp; best</span>"));
        assertTrue(page.contains("<li class=\"xml-node xml-property xml-condensable\""
                + " style=\"--xml-indent:1.84em\" role=\"treeitem\">"
                + "<div class=\"xml-row xml-nested-row\">"
                + "<span class=\"xml-tag-cell xml-property-name\">version</span>"
                + "<span class=\"xml-value xml-property-value\">1 &amp; 2</span>"));
        assertTrue(page.contains("class=\"xml-condense-toggle\" id=\"xml-condense-1\""));
        assertTrue(page.contains(
                "aria-label=\"Toggle leaf contraction for this branch\""));
        assertTrue(page.contains("<span class=\"xml-condensed-value\""
                + " title=\"version: 1 &amp; 2; empty: —\">"));
        assertTrue(page.contains("<input class=\"xml-map-expand-toggle\""
                + " id=\"xml-condense-1-pair-0\" type=\"checkbox\""
                + " aria-label=\"Toggle full key and value\">"
                + "<label class=\"xml-map-pair\""
                + " for=\"xml-condense-1-pair-0\">"
                + "<span class=\"xml-map-key\" title=\"version\">version</span>"
                + "<span class=\"xml-map-value\" title=\"1 &amp; 2\">"
                + "1 &amp; 2</span></label>"));
        assertFalse(page.contains("</span>: <span class=\"xml-map-value\""));
        assertTrue(page.contains("xml-leaf xml-condensable"));
        assertTrue(page.contains(".xml-children > .xml-node::before { content:\"\";"));
        assertTrue(page.contains(".xml-children > .xml-node:last-child::before {"));
        assertTrue(page.contains(
                ".xml-tree, .xml-children { display:flex; flex-direction:column;\n"
                + "  gap:.24em;"));
        assertTrue(page.contains(
                ".xml-node details[open] > .xml-children { margin-top:.24em; }"));
        assertTrue(page.contains(
                ".xml-condense-toggle:checked ~ .xml-children\n"
                + "  > .xml-condensable { display:none; }"));
        assertTrue(page.contains("cursor:pointer; pointer-events:auto;"));
        assertTrue(page.contains("flex:0 0 auto; margin:.125em 0 0 .38em;"));
        assertTrue(page.contains("grid-template-columns:14ch 28ch;"));
        assertTrue(page.contains("border-radius:.55em;"));
        assertTrue(page.contains(
                "grid-template-columns:minmax(14ch, max-content)\n"
                + "    minmax(28ch, max-content);"));
        assertTrue(page.contains(
                "grid-template-columns:21ch; grid-template-rows:auto auto;"));
        assertTrue(page.contains(
                ".xml-pill-layout-toggle:checked) .xml-map-key {\n"
                + "  font-size:.5em; }"));
        assertTrue(page.contains(
                ".xml-pill-layout-toggle:checked) .xml-empty {\n"
                + "  display:inline-grid; grid-template-rows:auto auto;"));
        assertTrue(page.contains(
                ".xml-pill-layout-toggle:checked) .xml-empty-mark {\n"
                + "  grid-row:1; transform:translateY(-.2em); }"));
        assertTrue(page.contains(
                ".xml-value:not(:has(> .xml-condensed-value))\n"
                + "  > .xml-normal-value:has(> .xml-empty) {\n"
                + "  display:flex; align-items:flex-start; }"));
        assertTrue(page.contains(
                ".xml-pill-layout-toggle:checked) .xml-empty::after {\n"
                + "  content:\"\\00a0\"; grid-row:2; font-size:.5em; }"));
        assertTrue(page.contains(
                "grid-template-columns:minmax(21ch, max-content);\n"
                + "  grid-template-rows:max-content max-content;"));
        assertTrue(page.contains("overflow:visible; text-overflow:clip;"));
        assertTrue(page.contains("text-overflow:ellipsis; white-space:nowrap;"));
        assertTrue(page.contains(
                ".xml-global-condense-toggle:checked)\n"
                + "  .xml-children > .xml-condensable { display:none; }"));
        assertTrue(page.contains(
                "> .xml-children.xml-all-condensable { display:flex; }"));
        assertTrue(page.contains(".xml-nested-row::after { content:\"\";"));
        assertTrue(page.contains(
                "left:calc(var(--xml-indent, 0em) + .06em);\n"
                + "  top:var(--xml-row-midline);"));
        assertFalse(page.contains(
                "left:calc(var(--xml-indent, 0em) - .5em); top:50%;"));
        assertTrue(page.contains("min-height:var(--xml-row-height);"));
        assertTrue(page.contains("padding:.28em 0; border-radius:.25em;"));
        assertTrue(page.contains("width:2.44em; border-top:1px solid #3f3f3f;"));
        assertTrue(page.contains(
                "z-index:1; width:2.44em; border-top:1px solid #3f3f3f;"));
        assertTrue(page.contains(".xml-row > * { position:relative; z-index:2; }"));
        assertTrue(page.contains(
                ".xml-row:hover { background:linear-gradient(to right,\n"
                + "  transparent 0 calc(var(--xml-indent, 0em) + .35em),"));
        assertTrue(page.contains(
                ".xml-node details { position:relative; margin:0; padding:0; }"));
        assertFalse(page.contains(
                ".xml-node details, .xml-node summary { margin:0; padding:0; }"));
        assertTrue(page.contains(
                ".xml-expandable > details > .xml-nested-row::after {\n"
                + "  width:1.37em; }"));
        assertTrue(page.contains(".xml-reader li { max-width:none; }"));
        assertTrue(page.contains("--xml-row-midline:0.955em;"));
        assertTrue(page.contains("--xml-row-height:1.910em;"));
        assertTrue(page.contains(
                "height:calc(var(--xml-row-midline) + .24em);"));
        assertTrue(page.contains(
                "left:calc(1.55em + var(--xml-indent, 0em));\n"
                + "  top:var(--xml-row-midline);"));
        assertTrue(page.contains("border-top:.5em solid transparent;"));
        assertTrue(page.contains("border-left:.75em solid #777;"));
        assertTrue(page.contains("transform:translate(-.125em, -50%);"));
        assertFalse(page.contains("content:\"▸\""));
        assertFalse(page.contains("content:\"▾\""));
        assertTrue(page.contains(
                ".xml-heading > :first-child { padding-left:1em; }"));
        assertTrue(page.contains(".xml-tag-cell {\n"
                + "  display:flex; align-items:flex-start;\n"
                + "  padding-left:calc(var(--xml-indent, 0em) + 2.65em); }"));
        assertTrue(page.contains("white-space:nowrap;\n"
                + "  color:#83c9ff; font-weight:700;"));
        assertTrue(page.contains(
                ".xml-tag-cell::after { content:\"\"; flex:1 1 auto;\n"
                + "  min-width:.5em;"));
        assertTrue(page.contains(
                "margin:calc(var(--xml-row-midline) - .28em) -1.2em 0 .45em;\n"
                + "  border-top:1px dotted #4a4a4a;"));
        assertTrue(page.contains(
                ".xml-node details[open]:has(\n"
                + "  > .xml-children:not(.xml-all-condensable))\n"
                + "  > summary > .xml-tag-cell::before,"));
        assertTrue(page.contains(
                "> .xml-condense-toggle:not(:checked)\n"
                + "    ~ .xml-children.xml-all-condensable)"));
        assertTrue(page.contains(
                "> .xml-condense-toggle:checked\n"
                + "    ~ .xml-children.xml-all-condensable)"));
        assertTrue(page.contains(
                "left:calc(var(--xml-indent, 0em) + 1.9em);\n"
                + "  top:calc(var(--xml-row-midline) - .28em + .65em);\n"
                + "  bottom:-.52em;"));
        assertTrue(page.contains("aria-label=\"No direct value\">"
                + "<span class=\"xml-empty-mark\">—</span></span>"));
        assertFalse(page.contains("<catalog"));
    }

    @Test
    void xmlReaderFailsClosedForExternalEntities(@TempDir Path tmp)
            throws java.io.IOException {
        Path secret = tmp.resolve("secret.txt");
        java.nio.file.Files.writeString(secret, "reader-must-not-load-this");
        String xml = "<!DOCTYPE root [<!ENTITY xxe SYSTEM \""
                + secret.toUri() + "\">]><root>&xxe;</root>";

        String page = SafeMarkupRenderer.page(
                content(DocumentKind.XML, xml), STYLE);

        assertTrue(page.contains("<ul class=\"xml-tree\" role=\"tree\">"));
        assertFalse(page.contains("reader-must-not-load-this"));
    }

    @Test
    void xmlReaderShowsInternalEntityText() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.XML,
                "<!DOCTYPE r [<!ENTITY e \"entity-text\">]><r><k>&e;</k><v>x</v></r>"),
                STYLE);

        assertTrue(page.contains("entity-text"));
    }

    @Test
    void xmlReaderWidensItsSharedScrollSurfaceForDeepTagPaths() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.XML, """
                <root><level><level><level><level><level><level>
                  <executionConfigurationForGeneratedSources>
                    <leaf>value</leaf>
                  </executionConfigurationForGeneratedSources>
                </level></level></level></level></level></level></root>
                """), STYLE);

        assertFalse(page.contains("--xml-tag-min-width:16.00em"));
        assertTrue(page.contains("--xml-reader-min-width:"));
        assertTrue(page.contains("white-space:nowrap;"));
    }

    @Test
    void markdownFormatsStructureButDropsLinkTargets() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.MARKDOWN,
                "# Heading\n\n[site](https://example.com)\n\n```java\n<x>\n```"), STYLE);
        assertTrue(page.contains("<h1 id=\"md-heading-0\">Heading</h1>"));
        assertTrue(page.contains("<span class=\"link\""
                + " data-link-location=\"https://example.com\">site</span>"));
        assertFalse(page.contains("href="));
        assertTrue(page.contains("&lt;"));
        assertTrue(page.contains("&gt;"));
        assertFalse(page.contains("<x>"));
    }

    @Test
    void markdownDoesNotEmphasizeIntrawordUnderscoresOrCodeSpans() {
        String body = SafeMarkupRenderer.markdownBody("""
                Intrinsic (AV2_PRED_ORACLE-confirmed, ~14).

                The _real emphasis_ and __strong__ stay.

                `_literal_` and `literal_emphasis_here` stay literal.
                """);

        assertTrue(body.contains(
                "<p>Intrinsic (AV2_PRED_ORACLE-confirmed, ~14).</p>"));
        assertTrue(body.contains(
                "<p>The <em>real emphasis</em> and <strong>strong</strong> stay.</p>"));
        assertTrue(body.contains("<code>_literal_</code>"));
        assertTrue(body.contains("<code>literal_emphasis_here</code>"));
        assertFalse(body.contains("AV2<em>"));
    }

    @Test
    void readerRendersEmphasisWithAVisibleBoldItalicFace() {
        String page = SafeMarkupRenderer.page(
                content(DocumentKind.MARKDOWN, "The *fundamental* gates."), STYLE);

        assertTrue(page.contains("<em>fundamental</em>"));
        assertTrue(page.contains("em { font-weight:700; }"));
    }

    @Test
    void markdownRendersTablesInsteadOfShowingPipeAndDelimiterCharacters() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.MARKDOWN, """
                | Change | In iris? |
                |:---|---:|
                | `Pps.java` field | ✅ already present |
                | PPS \\| SPS | **MISSING** |
                """), STYLE);

        assertTrue(page.contains(
                "<table class=\"markdown-table\"><colgroup>"));
        assertTrue(page.contains(
                "table.markdown-table { width:100%; max-width:78em; table-layout:fixed; }"));
        assertTrue(page.contains("<th class=\"align-left\">Change</th>"));
        assertTrue(page.contains("<th class=\"align-right\">In iris?</th>"));
        assertTrue(page.contains("<td class=\"align-left\"><code>Pps.java</code> field</td>"));
        assertTrue(page.contains("<td class=\"align-left\">PPS | SPS</td>"));
        assertTrue(page.contains("<td class=\"align-right\"><strong>MISSING</strong></td>"));
        assertFalse(page.contains("<p>|"));
        assertFalse(page.contains(":---"));
    }

    @Test
    void markdownTableKeepsCodeSpanPipesAndNormalizesUnevenRows() {
        String body = SafeMarkupRenderer.markdownBody("""
                Expression | Result
                --- | ---
                `left | right` | two
                short |
                extra | kept | ignored
                After table
                """);

        assertTrue(body.contains("<td><code>left | right</code></td><td>two</td>"));
        assertTrue(body.contains("<td>short</td><td></td>"));
        assertTrue(body.contains("<td>extra</td><td>kept</td>"));
        assertFalse(body.contains("ignored"));
        assertTrue(body.endsWith("</tbody></table><p>After table</p>"));
    }

    @Test
    void markdownTablePaddingStaysLinearUnderAWideHeader() {
        String md = "|a".repeat(128) + "|\n" + "|---".repeat(128) + "|\n"
                + "|\n".repeat(50_000);
        String body = assertTimeoutPreemptively(FAST,
                () -> SafeMarkupRenderer.markdownBody(md));

        assertTrue(body.startsWith("<table class=\"markdown-table\">"));
        assertTrue(body.length() < 20L * md.length(),
                "output stays linear: " + body.length());
    }

    @Test
    void markdownHeaderWiderThanTheColumnCapIsNotATable() {
        int columns = SafeMarkupRenderer.MAX_TABLE_COLUMNS + 1;
        String body = SafeMarkupRenderer.markdownBody(
                "|a".repeat(columns) + "|\n" + "|---".repeat(columns) + "|\n|\n");

        assertFalse(body.contains("<table"));
    }

    @Test
    void markdownBlankRunsInsideAListStayLinear() {
        String md = "- a\n" + "\n".repeat(200_000) + "- b\n";
        String body = assertTimeoutPreemptively(FAST,
                () -> SafeMarkupRenderer.markdownBody(md));

        assertEquals("<ul><li>a</li><li>b</li></ul>", body);
    }

    @Test
    void markdownTableBalancesContentWidthsWithoutGreedyColumns() {
        String twoColumns = SafeMarkupRenderer.markdownBody("""
                Java piece | macOS mapping
                --- | ---
                x | a deliberately long mapping description that must wrap without crushing its neighbour
                """);
        String body = SafeMarkupRenderer.markdownBody("""
                Key | Default | Range/Values | Meaning
                --- | --- | --- | ---
                `reader.fontSize` | 100 | 64–512 | base font size in pixels
                `reader.borderColor` | `#3c3c3c` | CSS color | border color (fallback on parse error)
                """);

        assertTrue(twoColumns.contains("<col style=\"width:30.00%\">"
                + "<col style=\"width:70.00%\">"));
        assertTrue(body.contains("<col style=\"width:40.00%\">"));
        assertTrue(body.contains("<col style=\"width:12.50%\">"));
        assertFalse(body.contains("<table><thead>"));
    }

    @Test
    void markdownFoldsSoftWrappedParagraphsAndListItems() {
        String body = SafeMarkupRenderer.markdownBody("""
                A soft-wrapped paragraph continues on the next
                physical line and keeps `inline code` intact.

                - A list item may wrap onto an indented continuation
                  line.
                - Inline code such as `Reader.open()` survives
                  the join.
                """);

        assertTrue(body.contains("<p>A soft-wrapped paragraph continues on the next "
                + "physical line and keeps <code>inline code</code> intact.</p>"));
        assertTrue(body.contains("<li>A list item may wrap onto an indented continuation "
                + "line.</li>"));
        assertTrue(body.contains("<li>Inline code such as <code>Reader.open()</code> survives "
                + "the join.</li>"));
        assertFalse(body.contains("</li><p>"));
    }

    @Test
    void markdownRendersTaskMarkersAsReadOnlyTextGlyphs() {
        String markdown = """
                - [ ] Pending
                - [] Compact pending
                - [x] Complete
                - [X] Uppercase complete
                - [check] Named complete
                """;
        String body = SafeMarkupRenderer.markdownBody(markdown);
        String page = SafeMarkupRenderer.page(
                content(DocumentKind.MARKDOWN, markdown), STYLE);

        assertTrue(body.contains("<ul class=\"task-tree\">"));
        assertTrue(body.contains("role=\"checkbox\" aria-checked=\"false\""
                + " aria-readonly=\"true\" aria-label=\"Unchecked task\""
                + " data-prefix=\"☐︎\""));
        assertTrue(body.contains("role=\"checkbox\" aria-checked=\"true\""
                + " aria-readonly=\"true\" aria-label=\"Completed task\""
                + " data-prefix=\"☑︎\""));
        assertTrue(body.contains(
                "class=\"task-row task-row-checkbox task-row-complete\""));
        assertTrue(body.contains(
                "<span class=\"task-label\">Named complete</span>"));
        assertFalse(body.contains("<li>[ ]"));
        assertFalse(body.contains("<li>[x]"));
        assertTrue(page.contains(
                "text-decoration:line-through; opacity:.50"));
        assertFalse(page.contains(
                ".task-row-complete .task-prefix"));
    }

    @Test
    void markdownTaskTreeUsesSiblingAndAncestorConnectors() {
        String body = SafeMarkupRenderer.markdownBody("""
                - [ ] Parent
                  - [ ] First child
                    - [x] Grandchild
                  - [check] Final child
                """);

        assertTrue(body.contains("data-connector=\"\""));
        assertTrue(body.contains("data-connector=\"├─ \""));
        assertTrue(body.contains("data-connector=\"│  └─ \""));
        assertTrue(body.contains("data-connector=\"└─ \""));
        assertTrue(body.contains("<ul class=\"task-tree\"><li"
                + " class=\"task-tree-item\"><div"));
    }

    @Test
    void markdownTaskContinuationWrapsInsideTheLabelColumn() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                """
                - [ ] Record lan42's hardware, OS build, filesystem, locale, terminal, and bundled
                      runtime versions in `docs/machine-profile-lan42.md`.
                """), STYLE);

        assertTrue(page.contains("<span class=\"task-label\">Record lan42&#39;s"
                + " hardware, OS build, filesystem, locale, terminal, and bundled"
                + " runtime versions in <code>docs/machine-profile-lan42.md</code>.</span>"));
        assertTrue(page.contains(
                "grid-template-columns:max-content max-content minmax(0,1fr)"));
        assertTrue(page.contains(
                ".task-row-checkbox .task-prefix::after"));
        assertTrue(page.contains("border-left:1px solid"));
    }

    @Test
    void taskLikeTextOutsideAListRemainsLiteral() {
        String body = SafeMarkupRenderer.markdownBody("""
                items[x] and [ ] prose

                - [ ]missing-space

                ```text
                - [x] code
                ```
                """);

        assertFalse(body.contains("role=\"checkbox\""));
        assertTrue(body.contains("<p>items[x] and [ ] prose</p>"));
        assertTrue(body.contains("<li>[ ]missing-space</li>"));
        assertTrue(body.contains(
                "<span class=\"syntax-plain\">- [x] code</span>"));
    }

    @Test
    void taskTreeKeepsNumbersAndNonTaskParents() {
        String body = SafeMarkupRenderer.markdownBody("""
                - Release work
                  3. [ ] Package
                  4. [x] Publish
                """);

        assertTrue(body.contains("aria-hidden=\"true\" data-prefix=\"•\""));
        assertTrue(body.contains("data-prefix=\"3. ☐︎\""));
        assertTrue(body.contains("data-prefix=\"4. ☑︎\""));
        assertFalse(body.contains(">☐︎<"));
        assertFalse(body.contains(">☑︎<"));
    }

    @Test
    void taskTreesSupportLooseQuotedAndTabIndentedLists() {
        String body = SafeMarkupRenderer.markdownBody("""
                > - [ ] Quoted parent
                >
                >   - [x] Quoted child

                - [ ] Loose parent

                \t- [check] Tab-indented child
                """);

        assertTrue(body.contains("<blockquote><ul class=\"task-tree\">"));
        assertTrue(body.contains(
                "<span class=\"task-label\">Quoted parent</span>"));
        assertTrue(body.contains(
                "<span class=\"task-label\">Quoted child</span>"));
        assertTrue(body.contains(
                "<span class=\"task-label\">Loose parent</span>"));
        assertTrue(body.contains(
                "<span class=\"task-label\">Tab-indented child</span>"));
        assertTrue(body.contains("data-connector=\"└─ \""));
    }

    @Test
    void htmlDropsScriptsAttributesAndEmbeddedResources() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.HTML,
                "<h1 onclick='bad()'>Safe</h1><script>alert(1)</script>"
                        + "<img src='https://bad.test/x'><p>Body</p>"
                        + "<a href='https://docs.test/a?x=1&amp;y=2'"
                        + " onclick='bad()'>Docs</a>"), STYLE);
        assertTrue(page.contains("<h1>Safe</h1>"));
        assertTrue(page.contains("<p>Body</p>"));
        assertTrue(page.contains("<span class=\"link\""
                + " data-link-location=\"https://docs.test/a?x=1&amp;y=2\">Docs</span>"));
        assertFalse(page.contains("<a"));
        assertFalse(page.contains("href="));
        assertFalse(page.contains("onclick"));
        assertFalse(page.contains("alert(1)"));
        assertFalse(page.contains("bad.test"));
    }

    @Test
    void htmlDropsMetadataAndStylesMisplacedAfterBodyStart() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.HTML, """
                <!doctype html><html><head>
                  <title>Head metadata</title>
                  <style>:root { --head-color: red; }</style>
                </head><body>
                  <title>Media Browser — full codebase review</title>
                  <style>
                    :root { --ground: #eff1f3; --ink: #14181c; }
                    body { background: var(--ground); color: var(--ink); }
                  </style>
                  <main><h1>Rendered review</h1><p>Reader content.</p></main>
                </body></html>
                """), STYLE);

        assertTrue(page.contains("<h1>Rendered review</h1>"));
        assertTrue(page.contains("<p>Reader content.</p>"));
        assertFalse(page.contains("Head metadata"));
        assertFalse(page.contains("Media Browser — full codebase review"));
        assertFalse(page.contains("--ground"));
        assertFalse(page.contains("background: var"));
    }

    @Test
    void htmlNestedPastTheDepthCapRendersAsSourceQuickly() {
        int n = 50_000;
        String unmatched = "<div>" + "<b>".repeat(n) + "</i>".repeat(n);
        String body = assertTimeoutPreemptively(FAST,
                () -> SafeMarkupRenderer.htmlBody(unmatched));
        assertTrue(body.startsWith("<pre class=\"plain\">&lt;div&gt;&lt;b&gt;"));

        String fonts = "<font>" + "<b>".repeat(n) + "</font>".repeat(n);
        assertTrue(assertTimeoutPreemptively(FAST,
                () -> SafeMarkupRenderer.htmlBody(fonts))
                .startsWith("<pre class=\"plain\">"));
    }

    @Test
    void htmlNestedWithinTheDepthCapRendersNormally() {
        String html = "<div>".repeat(100) + "<p>deep</p>" + "</div>".repeat(100);
        String body = SafeMarkupRenderer.htmlBody(html);

        assertFalse(body.contains("<pre class=\"plain\">"));
        assertTrue(body.contains("<div><div><p>deep</p></div></div>"));
    }

    @Test
    void htmlSeparatesAdjacentSpansWithoutChangingFollowingPunctuation() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.HTML,
                "<div><span class='chip'>high</span>"
                        + "<span class='chip'>build</span>"
                        + "<span class='chip'>CONFIRMED</span></div>"
                        + "<p><span>Hello</span>!</p>"), STYLE);

        assertTrue(page.contains("<div><span>high</span> "
                + "<span>build</span> <span>CONFIRMED</span></div>"));
        assertTrue(page.contains("<p><span>Hello</span>!</p>"));
    }

    @Test
    void htmlIsolatesSupplementaryGraphemesForJavaFxWebView() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.HTML,
                "<div>🦖 🦄 👨‍👩‍👧</div>"
                        + "<a href='https://docs.test/🦋'>Butterfly</a>"),
                STYLE);

        assertTrue(page.contains("<div><span class=\"unicode-glyph\">"
                + "&#x1f996;</span> <span class=\"unicode-glyph\">"
                + "&#x1f984;</span> <span class=\"unicode-glyph\">"
                + "&#x1f468;&#x200d;&#x1f469;&#x200d;&#x1f467;"
                + "</span></div>"));
        assertTrue(page.contains("data-link-location=\""
                + "https://docs.test/&#x1f98b;\""));
        assertFalse(page.contains("🦖"));
        assertFalse(page.contains("🦋"));
    }

    @Test
    void pageCspBlocksAllExternalContent() {
        String page = SafeMarkupRenderer.page(content(DocumentKind.PLAIN, "hello"), STYLE);
        assertTrue(page.contains("default-src 'none'"));
        assertTrue(page.contains("connect-src 'none'"));
    }

    @Test
    void readerSpacingTracksTheConfiguredDocumentFont() {
        String page = SafeMarkupRenderer.page(
                content(DocumentKind.MARKDOWN, "# Heading"), STYLE);

        assertTrue(page.contains("padding:2.3em"));
        assertTrue(page.contains("max-width:78em"));
        assertTrue(page.contains("padding:.35em .6em"));
        assertTrue(page.contains(
                "border-bottom:1px solid #383838; padding-bottom:.28em"));
        assertFalse(page.contains("rem"));
    }

    @Test
    void formattedReaderUsesProportionalTextAndMonospaceCode() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                "Readable `inline` text.\n\n```java\nclass Example {}\n```"),
                STYLE);

        assertTrue(page.contains(
                "font-family:'Reader Serif', \"Iowan Old Style\""));
        assertTrue(page.contains(
                "pre, code { font-family:'Menlo', ui-monospace"));
        assertTrue(page.contains("<code>inline</code>"));
        assertTrue(page.contains("<pre class=\"source-code\""
                + " style=\"--source-line-digits:1ch\"><code>"));
        assertTrue(page.contains("<span class=\"source-line-number\""
                + " aria-hidden=\"true\">1</span>"));
        assertTrue(page.contains("<span class=\"syntax-keyword\">class</span>"));
    }

    @Test
    void markdownCodeBlocksUseSourceHighlightingAndRestartLineNumbers() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                """
                ```java
                public class Example {
                    String value = "hello";
                }
                ```

                ```text
                For prose
                ```
                """), STYLE);

        assertTrue(page.contains("<span class=\"syntax-keyword\">public</span>"));
        assertTrue(page.contains("<span class=\"syntax-string\">&quot;hello&quot;</span>"));
        assertTrue(page.contains("<span class=\"source-line-number\""
                + " aria-hidden=\"true\">3</span>"));
        assertTrue(page.contains("<span class=\"syntax-plain\">For prose</span>"));
        assertTrue(page.indexOf("aria-hidden=\"true\">1</span>")
                != page.lastIndexOf("aria-hidden=\"true\">1</span>"));
        assertTrue(page.contains(".syntax-keyword { color:"
                + SyntaxHighlighter.color(SyntaxHighlighter.Style.KEYWORD)));
    }

    @Test
    void blankCodeLinesCarryNoCopyableCharacters() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                "```text\nfirst\n\nlast\n```"), STYLE);

        assertTrue(page.contains("<span class=\"source-line-text\">"
                + "</span></span>"));
        assertFalse(page.contains("&#8203;"));
        assertFalse(page.contains("\u200b"));
        assertTrue(page.contains(
                ".source-line-text:empty::before { content:\"\\200b\"; }"));
    }

    @Test
    void markdownCodeBlocksCanUseTheNoneEngine() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                "```java\npublic class Example {}\n```"),
                STYLE, HighlightEngines.NONE);

        assertTrue(page.contains(
                "<span class=\"syntax-plain\">public class Example {}</span>"));
        assertFalse(page.contains(
                "<span class=\"syntax-keyword\">public</span>"));
    }

    @Test
    void markdownTclCodeBlocksUseTclHighlighting() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                """
                ```tcl
                proc greet {name} {
                    puts $name
                }
                ```
                """), STYLE);

        assertTrue(page.contains(
                "<span class=\"syntax-keyword\">proc</span>"));
        assertTrue(page.contains(
                "<span class=\"syntax-key\">$name</span>"));
    }

    @Test
    void markdownCodeBlockReservesOneSharedLineNumberWidth() {
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                """
                ```text
                one
                two
                three
                four
                five
                six
                seven
                eight
                nine
                ten
                ```
                """), STYLE);

        assertTrue(page.contains("style=\"--source-line-digits:2ch\""));
        assertTrue(page.contains(
                "min-width:var(--source-line-digits, 1ch)"));
        assertTrue(page.contains("aria-hidden=\"true\">1</span>"));
        assertTrue(page.contains("aria-hidden=\"true\">10</span>"));
    }

    @Test
    void formattedReaderAlwaysWrapsLongCodeBlockLines() {
        var sourceNoWrap = new ReaderStyle(
                "Reader Serif", "Menlo", 15, false, false,
                1.35, 0, false, 4);
        String page = SafeMarkupRenderer.page(content(
                DocumentKind.MARKDOWN,
                "```text\n--enable-native-access=one.extremely.long.module.name\n```"),
                sourceNoWrap);

        assertTrue(page.contains(
                "pre { padding:1em; overflow-x:hidden; white-space:pre-wrap;"));
        assertTrue(page.contains(
                "overflow-wrap:anywhere; word-break:break-word;"));
    }

    private static DocumentContent content(DocumentKind kind, String text) {
        return new DocumentContent(Path.of("test.txt"), kind, text, StandardCharsets.UTF_8,
                text.length(), 0, new int[]{0});
    }
}
