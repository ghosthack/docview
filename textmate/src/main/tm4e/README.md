# Eclipse TM4E core

This directory vendors the pure-Java core of
[Eclipse TM4E](https://github.com/eclipse-tm4e/tm4e) at commit
`4b15c1b2c1d24b06556065d5006804ab56e9c211` (2026-07-27). It is compiled as an
additional Maven source root because TM4E releases are distributed as
Eclipse/p2 bundles rather than Maven Central artifacts.

The source remains under EPL-2.0; see `LICENSE`. Changes made for docview:

- packages relocated from `org.eclipse.tm4e` to
  `io.github.ghosthack.docview.textmate.internal.tm4e`, so docview never
  clashes with a real TM4E on the class or module path (internal, not API);
- removed the CSS theme support (`core.theme.css`, `core.internal.theme.css`)
  and the YAML grammar/theme reader (`TMParserYAML`); the raw grammar and
  theme readers now reject YAML sources. This drops the Batik and SnakeYAML
  dependencies, which docview's bundled JSON/plist grammars never needed;
- generic-capture compatibility for newer JDKs in `ObjectCloner`;
- ignoring non-numeric metadata in TextMate capture maps, matching
  vscode-textmate's tolerant behavior;
- lowering tokenizer diagnostics, optional/missing-grammar warnings, and regex
  warnings to debug level, matching vscode-textmate's debug-only reporting.

This source is shipped in docview-textmate's sources jar and its license and
these notes in the binary jar under `META-INF/licenses/tm4e/`.
