# Bundled TextMate grammars

These grammar files are redistributed unmodified. All but the Markdown
grammar come from the [Microsoft VS Code](https://github.com/microsoft/vscode)
repository at commit `3cd0ed67506d43e342dcf2835e719cd64de68ffc`, which
bundles them from the upstream projects below (see each extension's
`cgmanifest.json` there). The
VS Code repository's MIT license is included as `LICENSE-VSCODE.txt`; the
TextMate Bundle License is included as `LICENSE-TEXTMATE-BUNDLES.txt`.

| File | Scope | Upstream (commit) | License |
|---|---|---|---|
| `css.tmLanguage.json` | `source.css` | [microsoft/vscode-css](https://github.com/microsoft/vscode-css) (`9a07d76`) | MIT |
| `html.tmLanguage.json` | `text.html.basic` | [textmate/html.tmbundle](https://github.com/textmate/html.tmbundle) (`0c3d5ee`), converted by VS Code | TextMate Bundle License |
| `java.tmLanguage.json` | `source.java` | [redhat-developer/vscode-java](https://github.com/redhat-developer/vscode-java) (`f09b712`) | MIT |
| `javascript.tmLanguage.json` | `source.js` | [microsoft/TypeScript-TmLanguage](https://github.com/microsoft/TypeScript-TmLanguage) (`48f6086`) | MIT |
| `javascript-regexp.tmLanguage` | `source.js.regexp` | [textmate/javascript.tmbundle](https://github.com/textmate/javascript.tmbundle) (`fccf0af`) | TextMate Bundle License |
| `json.tmLanguage.json` | `source.json` | [microsoft/vscode-JSON.tmLanguage](https://github.com/microsoft/vscode-JSON.tmLanguage) (`9bd83f1`) | MIT |
| `markdown.tmLanguage` | `text.html.markdown` | [textmate/markdown.tmbundle](https://github.com/textmate/markdown.tmbundle), as carried by [eclipse-tm4e/tm4e](https://github.com/eclipse-tm4e/tm4e) (`4b15c1b`) because VS Code's grammar needs regex features Joni lacks | TextMate Bundle License |
| `python.tmLanguage.json` | `source.python` | [MagicStack/MagicPython](https://github.com/MagicStack/MagicPython) (`7d0f2b2`) | MIT |
| `python-regexp.tmLanguage.json` | `source.regexp.python` | [MagicStack/MagicPython](https://github.com/MagicStack/MagicPython) (`c9b3409`) | MIT |
| `ruby.tmLanguage.json` | `source.ruby` | [Shopify/ruby-lsp](https://github.com/Shopify/ruby-lsp) (`ba41f8b`) | MIT |
| `shell.tmLanguage.json` | `source.shell` | [jeff-hykin/better-shell-syntax](https://github.com/jeff-hykin/better-shell-syntax) (`35020b0`) | MIT |
| `sql.tmLanguage.json` | `source.sql` | [microsoft/vscode-mssql](https://github.com/microsoft/vscode-mssql) (`c002f51`) | MIT |
| `typescript.tmLanguage.json` | `source.ts` | [microsoft/TypeScript-TmLanguage](https://github.com/microsoft/TypeScript-TmLanguage) (`48f6086`) | MIT |
| `xml.tmLanguage.json` | `text.xml` | [atom/language-xml](https://github.com/atom/language-xml) (`7bc75df`) | MIT |
| `yaml.tmLanguage.json` | `source.yaml` | [textmate/yaml.tmbundle](https://github.com/textmate/yaml.tmbundle) (`e54ceae`), converted by VS Code | TextMate Bundle License |

The Eclipse TM4E core that tokenizes these grammars is EPL-2.0; its license
and notes ship in this jar under `META-INF/licenses/tm4e/`.
