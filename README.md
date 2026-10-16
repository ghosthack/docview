# docview

Safe, fast document readers for Java. docview parses Markdown, HTML and XML
into compact row models and draws them with virtualized JavaFX views, so a
document appears in roughly constant time regardless of its size (parsing is
linear and can run ahead of time on background threads). It was built for
fast keyboard browsing of document folders, where a WebView reload per
document was the bottleneck.

| Module | What it contains | Depends on |
|---|---|---|
| `docview-core` | Markdown / HTML / XML parsers and row models, a sanitized HTML page renderer for WebView, document classification, the pluggable highlighting API with Plain and Vanilla engines, background build caches | JDK only |
| `docview-fx` | `BlockView` (Markdown and HTML, with selection and copy) and `XmlTreeView` | core, your JavaFX 21+ |
| `docview-textmate` | TextMate syntax highlighting (vendored Eclipse TM4E core and bundled grammars), registered through `ServiceLoader` | core, Gson, Joni, JCodings |

Requirements: Java 21+. `docview-fx` declares JavaFX as `provided`; the
application supplies its own JavaFX 21 or newer.

## Maven

```xml
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>docview-fx</artifactId>
  <version>0.1.0</version>
</dependency>
<!-- Optional: TextMate highlighting for code blocks -->
<dependency>
  <groupId>io.github.ghosthack</groupId>
  <artifactId>docview-textmate</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Usage

### Models (`docview-core`)

```java
import io.github.ghosthack.docview.*;

Blocks.Model markdown = Blocks.fromMarkdown(text, HighlightEngines.AUTOMATIC);
Blocks.Model html = Blocks.fromHtml(htmlText);  // same row model
XmlTree.Tree xml = XmlTree.parse(xmlText);      // root == null on error

for (Blocks.Row row : markdown.rows()) {
    switch (row) {
        case Blocks.Heading h -> System.out.println("#".repeat(h.level()) + " …");
        case Blocks.CodeLine c -> System.out.println(c.text());
        default -> { }
    }
}
markdown.close(); // optional: releases syntax state early
```

Rows are flat and short — a heading, paragraph, list item, table row or
single code line — so a view only builds what is on screen. Code tokens are
computed per line on demand.

### Views (`docview-fx`)

```java
import io.github.ghosthack.docview.fx.BlockView;
import io.github.ghosthack.docview.fx.ReaderTheme;
import io.github.ghosthack.docview.fx.XmlTreeView;

var reader = new BlockView();
reader.setLinkActivated(target -> System.out.println("link: " + target));
reader.show(path, Blocks.fromMarkdown(text, HighlightEngines.AUTOMATIC),
        ReaderStyle.defaults());
root.setCenter(reader.node());

var tree = new XmlTreeView();
tree.setTheme(ReaderTheme.light());
tree.show(path, XmlTree.parse(xmlText), ReaderStyle.defaults(), xmlText);
root.setRight(tree.node());
```

`BlockView` supports mouse selection across rows, `Shortcut+C` / `Shortcut+A`
(configurable with `setCopyKey` / `setSelectAllKey`), clickable links,
heading anchors (`scrollToAnchor`) and per-document scroll memory.
`XmlTreeView` shows *Node tag* / *Node value* columns with leaf contraction
into key/value pills (globally or per branch), vertical or horizontal pills,
row copy, and per-document expansion and scroll memory. Both views accept an
optional `setOnDoubleClick` action.

Link activation hands the callback the document's raw link target, an
untrusted string (it may be `javascript:`, `file:`, a UNC path, …). Validate
the scheme or path before opening anything.

Views never close models; a `BlockCache` (or the caller) owns them. Closing
only releases syntax state early, since an unreachable model is reclaimed.

### Theming

`ReaderTheme.dark()` (default) and `ReaderTheme.light()` are presets; adjust
any color, including syntax roles, with a builder:

```java
var theme = ReaderTheme.dark().toBuilder()
        .link(Color.web("#ffb86c"))
        .syntax(HighlightRole.KEYWORD, Color.web("#ff79c6"))
        .build();
reader.setTheme(theme);
```

`ReaderStyle` sets fonts, size, line spacing, wrapping and tab size.

### Prebuilding

`BlockCache`, `XmlTreeCache` and `PageCache` build models or pages on
background threads with a small LRU: `request` for the current document
(joins a build in flight), `prefetch` for neighbours at low priority, and
`newGeneration` to drop prefetches that went stale.

```java
var models = new BlockCache();
DocumentContent next = DocumentContent.of(nextPath, DocumentKind.MARKDOWN, nextText);
models.prefetch(BlockCache.Key.of(next, HighlightEngines.AUTOMATIC), next);
```

### WebView pages

`SafeMarkupRenderer.page(document, style, engine)` produces a complete,
script-free HTML page for a WebView, from the same grammar and whitelist the
native views use.

## Safety

- HTML passes a structural whitelist: scripts, styles, active attributes,
  images, frames, objects and remote resources are discarded. Link targets
  survive only as inert metadata; nothing navigates or fetches.
- Generated WebView pages carry a deny-by-default content security policy.
- XML is parsed with external entities, DTDs, schemas and XInclude disabled.
  The tree is capped at 20,000 nodes, depth 128 and 20,000 leaf pills, and
  inputs over 16 Mi characters are refused before a DOM is built.
- Markdown quote and list nesting is capped at depth 32, and inline parsing is
  linear in the line length.
- Highlighting is bounded: TextMate skips lines over 2,000 characters,
  interrupts any line still tokenizing after 100 ms, and stops after about
  1 s per code block; each parsed document also has a shared budget of about
  1.5 s. Past these limits code renders plain.
- When XML cannot be shown as a tree, at most 1 Mi characters of its source
  are shown instead.

## Highlighting

`HighlightEngines` selects an engine per document: `automatic` prefers the
highest-priority engine that supports the language (TextMate when
`docview-textmate` is on the classpath, else Vanilla), with `vanilla` and
`none` always available. Engines can be added with `ServiceLoader` or
`HighlightEngines.register`. A failing engine degrades to Vanilla and then
plain text.

## License

MIT for docview. `docview-textmate` also contains the Eclipse TM4E core under
EPL-2.0 and grammars under their own licenses; see
[THIRD-PARTY.md](THIRD-PARTY.md).
