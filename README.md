# Razomy Notation (`.rn`) — JetBrains IntelliJ IDE Plugin

Official JetBrains IDE plugin providing full language support for **Razomy Notation (`.rn`)** — the Layer 0 ontological data, outliner, schema, and markup notation.

<!-- Plugin description -->
Full language support for **Razomy Notation (`.rn`)** v0.3.0 — Layer 0 Universal Node & Schema Notation:
- **Context-Aware Lexer & Syntax Highlighting**: Symmetric Pentad (`[` Stream, `{` Form, `(` Tuple, `"` Raw/Scalar String, `#` Comment), Attached Props & Slots (`Key{Props}(Slots)`), Attached Type Constructors (`Sql(`, `Html[`, `Scss{`, `Date"..."`), Schema Type Definitions (`Uuid{'pk}`, `Int(18, 120)`, `Str`), Prefix-Quoted Symbols (`'pk`, `'GET`), Unified `None`, and Algebraic Composition Operators (`&`, `^`, `/`).
- **Multi-Host Language Injection**: Automatic syntax highlighting and IDE completions inside Column-0 Multiline Raw Strings (`Json"`, `Ts"`, `Sql"`, `Html"`, `Scss"`, `Sh"`, `Md"`, `Tf"`, `Rn2"`, etc.).
- **2D Relational Table Formatter (`Ctrl+Alt+L` / `Cmd+Option+L`)**: Aligns comma-separated 2D Tuple tables into clean visual columns while preserving Column-0 raw strings and comments untouched.
- **Visual Auto-Numbering Inlay Hints**: Automatic hierarchical numbering for `Md` headings (`part` → `1.`, `section` → `1.1.`, `topic` → `1.1.1.`), Ordered Lists (`ol [`), and 2D Relational Tables (`Table N` badges and row numbers).
- **Topological Code Folding & Commenter**: Fold any indented block (`[`, `{`, `(`, or `#` at EOL) and toggle inline `# ` comments with `Ctrl+/` (`Cmd+/`).
- **Multi-Format Exporter & Tool Window**: Convert and preview `.rn` files in JSON, YAML, Markdown, HTML, SQL, SCSS, Docker/Terraform, Protobuf, Mermaid, CSV, or Canonical 1-Space `.rn`.
<!-- Plugin description end -->

---

## Features

### 1. Syntax Highlighting & Color Settings
Customizable syntax highlighting under **Settings / Preferences → Editor → Color Scheme → Razomy Notation (.rn)**:
- **Symmetric Pentad**: `[` Stream, `{` Form, `(` Tuple, `"` String/Raw, `#` Inline & Indented Block Comments
- **The Space Law & Type Constructors**: Distinguishes `Key Value` from attached `PascalCase` Type Constructors (`Sql(`, `Html[`, `Scss{`, `Date"2026-03-30"`)
- **Schema Archetypes**: Highlights `PascalCase` schema types (`Uuid{'pk}`, `Str`, `Int(18, 120)`)
- **Algebraic Inheritance**: Highlights `&` (merge), `^` (symmetric difference), `/` (key strip)

### 2. Embedded Language Injection (Column-0 Raw Strings)
Any Column-0 multiline raw string prefixed with a language constructor (`Ts"`, `Json"`, `Sql"`, `Html"`, `Scss"`, `Sh"`, `Py"`, `Rs"`, `Kt"`, `Md"`, `Rn2"`, etc.) automatically injects the corresponding IntelliJ language parser inside the block.

```rn
query Sql"
SELECT id, username, role
FROM users
WHERE active = true;
"

script Ts1"
export const greet = (name: string): string => `Hello, ${name}!`;
"1
```

### 3. 2D Table Visual Column Formatter
Use **Code → Reformat Code** (`Ctrl+Alt+L` / `Cmd+Option+L`) to automatically align 2D Relational Tables (`(` blocks whose first child line contains top-level commas):

```rn
users_schema (
 column, type, constraint, default
 id,     Uuid, 'pk,        gen_uuid
 org_id, Uuid, Orgs.id,    None
 role,   Text, 'not_null,  member
```

### 4. Visual Auto-Numbering Inlay Hints
Displays non-intrusive editor inlay badges for:
- Document headings (`part`, `section`, `topic`)
- Ordered lists (`ol [`)
- 2D Relational Table headers (`Table 1`) and aligned row indices (`1.`, `2.`, ...)

### 5. Multi-Format Exporter & Preview Tool Window
Open the **Razomy Notation (.rn)** tool window on the right sidebar or right-click any `.rn` file in the Editor / Project View (**Export Razomy Notation (.rn)**) to export to:
- **JSON** (`.json`)
- **YAML** (`.yaml`)
- **Markdown** (`.md`)
- **HTML** (`.html`)
- **SQL** (`.sql`)
- **SCSS / CSS** (`.scss`)
- **Docker & Terraform** (`.tf`)
- **Protocol Buffers v3** (`.proto`)
- **Mermaid Diagram** (`.mmd`)
- **CSV** (`.csv`)
- **Canonical 1-Space `.rn`** (`.formatted.rn`)

> If the `rn` CLI binary is available on `PATH` or in your workspace `target/release/rn`, the exporter delegates to the native Rust compiler; otherwise, it uses the built-in JVM converter fallback.

---

## Development & Release Commands

### Run IDE with Plugin
```bash
./gradlew runIde
```

### Run Tests & Plugin Verification
```bash
./gradlew check
./gradlew verifyPlugin
```

### Build Distributable Plugin Archive (`.zip`)
```bash
./gradlew buildPlugin
```
The packaged plugin archive will be generated in `build/distributions`.

### Publish to JetBrains Marketplace
Ensure `PUBLISH_TOKEN` (and optional signing credentials `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) are set in your environment:
```bash
./gradlew publishPlugin
```

---

## License

Copyright © [Razomy](https://razomy.org). All rights reserved.
