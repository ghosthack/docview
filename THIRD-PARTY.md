# Third-party components

## docview-textmate

- **Eclipse TM4E core**, vendored from
  [eclipse-tm4e/tm4e](https://github.com/eclipse-tm4e/tm4e) at commit
  `4b15c1b2c1d24b06556065d5006804ab56e9c211` and relocated from
  `org.eclipse.tm4e.*` to `io.github.ghosthack.docview.textmate.internal.tm4e.*`.
  Eclipse Public License 2.0.
  Source, license and the list of local changes: `textmate/src/main/tm4e/`
  (also packaged in the jar under `META-INF/licenses/tm4e/`).
- **TextMate grammars** for CSS, HTML, Java, JavaScript, JSON, Markdown,
  Python, Ruby, shell, SQL, TypeScript, XML and YAML, from the VS Code
  repository and upstream grammar projects, under MIT or the TextMate Bundle
  License. Per-file sources, commits and licenses:
  `textmate/src/main/resources/io/github/ghosthack/docview/textmate/grammars/`
  (`NOTICE.md`, `LICENSE-VSCODE.txt`, `LICENSE-TEXTMATE-BUNDLES.txt`),
  packaged alongside the grammars.

Runtime dependencies of docview-textmate (resolved from Maven Central): Gson,
JCodings and Joni, and optionally the Eclipse JDT null annotations.

docview-core and docview-fx have no third-party runtime dependencies beyond
the JDK and the application's own JavaFX.
