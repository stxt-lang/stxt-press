# stxt-press

A service that makes PDF and SVG from [STXT](https://stxt.dev) layout documents (`dev.stxt.pdf`), with their Slots
filled with JSON data: an HTTP API, a web editor and a command line, in one jar. The layout, the PDF and the SVG pages
are the work of [stxt-pdf](https://github.com/stxt-lang/stxt-pdf), which writes the PDF with
[Apache PDFBox](https://pdfbox.apache.org), and the fill of the Slots, of
[stxt-transform](https://github.com/stxt-lang/stxt-java-transform): this project does not parse, validate or lay out
anything itself.

Version 0.7.0. The routes, `server.stxt` and the Java API may still change between minor versions before 1.0.

## Start

With Docker, the image of the last release:

```bash
docker run --rm -p 127.0.0.1:8080:8080 ghcr.io/stxt-lang/stxt-press
```

The server listens on `http://127.0.0.1:8080/`, where the [index and the editor](#index-and-editor) are, with the
examples, and Ctrl+C stops it ([Docker](#docker)).

Without Docker, the folder [`server/`](server/) is a whole server: the jar, the scripts that start it, its `server.stxt`, the IBM Plex
fonts, nineteen example templates and documents, and a reference of the layout language. It comes with the source of
each [release](https://github.com/stxt-lang/stxt-press/releases) and with a clone of the repository, and needs
**Java 17** or later:

```bash
server/start.sh
```

On Windows, `server\start.bat`. The server listens on the same address. A template, filled with its sample data or
with other data, and a document:

```bash
curl -o invoice.pdf http://127.0.0.1:8080/print/examples/invoice
curl -o invoice.pdf -d @server/templates/examples/invoice.json http://127.0.0.1:8080/print/examples/invoice
curl -o blue-hour.pdf http://127.0.0.1:8080/documents/examples/blue-hour
```

The jar attached to each release is that same `server/stxt-press.jar`. Alone, it needs a [`server.stxt`](#configuration)
and a resources folder with the fonts: without them, `start` stops with `The fonts folder .../resources/fonts does not
exist`. The folder can be copied anywhere, and its [`README.md`](server/README.md) lists what each part has.

## Docker

The image is published at `ghcr.io/stxt-lang/stxt-press`, for `linux/amd64` and `linux/arm64`: one tag for each
version (`0.7.0`), and `latest` for the last release. It is the folder `server/` on `eclipse-temurin:17-jre`, with two
changes to its `server.stxt`: `Host: 0.0.0.0`, so that it answers from outside the container, and `Save: false`.

```bash
docker run --rm -p 127.0.0.1:8080:8080 ghcr.io/stxt-lang/stxt-press:0.7.0
```

The image answers without token, to anyone who reaches its port: `-p 127.0.0.1:8080:8080` keeps it on the machine.
The folder is `/opt/stxt-press`, and the server runs as the user `stxt-press` (uid `10001`). It warms up before
answering, and its health check calls `/health`.

Own templates, documents or resources replace those of the image:

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
    -v "$PWD/templates:/opt/stxt-press/templates:ro" \
    -v "$PWD/documents:/opt/stxt-press/documents:ro" \
    -v "$PWD/resources:/opt/stxt-press/resources:ro" \
    ghcr.io/stxt-lang/stxt-press:0.7.0
```

A token, other routes or other limits need an own `server.stxt`, with `Host: 0.0.0.0`. Its relative paths are relative
to `/opt/stxt-press`, and the token comes in the variable that its `Token env` names. With `Save: true`, the folders
are mounted without `:ro` and writable by the uid `10001`:

```bash
docker run --rm -p 8080:8080 \
    -v "$PWD/server.stxt:/opt/stxt-press/server.stxt:ro" \
    -e STXT_PRESS_TOKEN="$(cat token.txt)" \
    ghcr.io/stxt-lang/stxt-press:0.7.0
```

`docker stop` gives the server 10 seconds before it kills it, and a conversion in progress has the `Timeout` of
`server.stxt` (30 seconds by default): `docker run --stop-timeout 40` lets it end.

The image is built from the root of the repository after `mvn package`, which leaves in `server/` the jar of the
code:

```bash
mvn package -Dnpm.install=ci
docker build -t stxt-press .
```

## Templates and documents

The server prints two kinds of files, each from its own folder:

* **A template** (`/print`) is a layout document with Slots, filled with the JSON data of the request, or with its
  sample data.
* **A document** (`/documents`) is a layout document printed as it is, without data.

```
templates/
    .styles.stxt                the styles of every template below
    invoices/
        .styles.stxt            the styles of the templates of invoices/
        invoice.stxt            the template invoices/invoice
        invoice.styles.stxt     its own styles
        invoice.json            its sample data
```

A template is named by its path without extension (`invoices/invoice`). Its own styles, its sample data and the
`.styles.stxt` of its folders are optional. The styles apply from the root to the template, and the nearest one wins.
Each segment of a path is `[A-Za-z0-9_-]+`, and a file that is a symbolic link leaving the folder is not read.

A document follows the same rules, without data: a `<name>.json` beside it is not read. The folders are read on every
request, so a file changed on disk counts without restarting.

## Routes

The routes of the clients:

| Route | What it does |
|---|---|
| `GET /print/{path}` | The template `{path}`, filled with its sample data |
| `POST /print/{path}` | The template `{path}`, filled with the body: the JSON data as they are |
| `GET /documents/{path}` | The document `{path}`, as it is |
| `POST /render` | Any document: `{"document": "...", "styles": ["..."], "data": {...}, "options": {...}}` |
| `GET /schema` | The schema `dev.stxt.pdf` of the engine |
| `GET /health` | `{"status": "ok", "versions": {"stxt-press": "0.7.0", "stxt-pdf": "0.19.0"}}`, without token |

`/print`, `/documents` and `/render` answer with a PDF (`?format=pdf`, the default), one SVG per page (`?format=svg`,
as `{"pages": [...]}`) or the filled document (`?format=stxt`). `&debug=true` paints the outlines of the boxes. Every
PDF names its producer in its document information and its XMP: `stxt-press 0.7.0 (stxt-pdf 0.19.0)`, or the
`Producer` of `server.stxt`.

The routes of the editor:

| Route | What it does |
|---|---|
| `GET /files/templates`, `GET /files/documents` | The templates or the documents, with the files each one has |
| `GET /files/templates/{path}` | The document, the chain of styles and the sample data of a template, with an `ETag` |
| `GET /files/documents/{path}` | The document and the chain of styles of a document, with an `ETag` |
| `PUT /files/templates/{path}`, `PUT /files/documents/{path}` | Writes or creates a template or a document, with [`Save`](#saving) on |
| `GET /fonts` | The font families of the server, and each face (family, weight, style) with the id of its file |
| `GET /fonts/{id}` | The file of a face, by its SHA-256 |
| `GET /` | The [index](#index-and-editor), without token |
| `GET /editor`, `GET /editor/templates/{path}`, `GET /editor/documents/{path}` | The [editor](#index-and-editor), blank, on a template or on a document, without token |

### Errors

An error is a JSON body with its `code` and its `message`:

| Status | When |
|---|---|
| `400` | The body or a parameter is not valid (`BODY_NOT_VALID`, `PARAMETER_NOT_VALID`) |
| `401` | The server has a token and the request does not carry it (`Authorization: Bearer <token>`) |
| `403` | The server has no token, listens on a loopback address and the `Host` of the request is not a local name (`HOST_NOT_ALLOWED`) |
| `404` | The route, the template or the document does not exist (`NOT_FOUND`, `TEMPLATE_NOT_FOUND`, `DOCUMENT_NOT_FOUND`) |
| `405` | The method is not one of the route, which the `Allow` header lists |
| `409` | A save from a version that is not that of the files any more (`FILES_CHANGED`) |
| `412` | A creation where there is already a template or a document (`TEMPLATE_EXISTS`, `DOCUMENT_EXISTS`) |
| `413` | The body is larger than the server accepts (`BODY_TOO_LARGE`), or the document exceeds a limit of the engine |
| `415` | A save without `Content-Type: application/json` (`CONTENT_TYPE_NOT_VALID`) |
| `422` | The document, its styles or its data are not valid |
| `428` | A save without `If-Match` or `If-None-Match` (`VERSION_REQUIRED`) |
| `503` | The conversion ran out of time (`CANCELLED`), the server is busy (`SERVER_BUSY`, with `Retry-After`) or stopping (`SERVER_BUSY`), or it is warming up (`SERVER_STARTING`, with `Retry-After`, `/health` too) |
| `500` | An error of the server: its resources, its folders, the engine. The message has no details, and the error goes to the log |

The errors of a conversion (`413`, `422`, `503`) also carry `kind`, `line` and `source`, the input the line belongs to:
`document`, `data`, `filled` (the document after filling its Slots, which the body carries in `filled`), the place of
a styles document in `/render` (`styles[1]`) or the path of a file of a template or a document
(`invoices/.styles.stxt`).

```json
{"kind": "INVALID_DOCUMENT", "code": "JSON_NOT_VALID", "line": 2, "source": "data", "message": "..."}
```

### Security

The server has no TLS or users: it is meant to run behind a reverse proxy that adds them.

* **Token**: with `Token env` in `server.stxt`, every route but `/health` and the pages asks for
  `Authorization: Bearer <token>`.
* **Local names**: a server without token on a loopback address, such as the default `127.0.0.1`, answers only to
  requests whose `Host` is `localhost`, `127.0.0.1`, `[::1]` or the `Host` of `server.stxt`, with any port. Any other,
  a missing one included, gets `403` (`HOST_NOT_ALLOWED`). Otherwise a page of another site open in the browser could
  make its own name resolve to `127.0.0.1` (DNS rebinding) and use the server as if it were of the same origin. A
  server reached through another name, such as an alias of the `hosts` file, needs a token or that name in `Host`.
* **Time**: a request has 30 seconds to arrive whole, and its response the `Timeout` of `server.stxt` and 30 seconds
  more; otherwise the connection is closed. They are the properties `sun.net.httpserver.maxReqTime` and `maxRspTime` of
  the JDK, which `start` sets unless they are given with `-D`:

  ```bash
  java -Dsun.net.httpserver.maxReqTime=120 -jar stxt-press.jar start
  ```
* **Routes off**: `/render` converts any document, and `/files/` shows the texts and the sample data of the folders.
  `Render: false` and `Files: false` in the `Routes` of `server.stxt` turn them off, and the templates and the
  documents are still printed. `Editor: false` turns off `/`, `/editor`, `/assets/` and `/fonts`. With the three off,
  the server has `/print`, `/documents`, `/schema` and `/health`.

### Saving

With `Save: true` in the `Routes` of `server.stxt` (off by default), `PUT /files/templates/{path}` and
`PUT /files/documents/{path}` write the files of a template or a document: the **Save** of the editor. The body has the
shape of the `GET`, with the texts to write, and `If-Match` has the `ETag` of the `GET` they come from. A save from
another version writes nothing (`409`, `FILES_CHANGED`), so that it does not undo a change made since, on disk or by
someone else.

```bash
curl -X PUT -H 'Content-Type: application/json' -H 'If-Match: "<the ETag of the GET>"' \
    --data-binary '{"document": "Document (dev.stxt.pdf): ...", "styles": [{"path": "examples/invoice.styles.stxt", "text": "..."}], "data": "{...}"}' \
    http://127.0.0.1:8080/files/templates/examples/invoice
```

Only `document` is required. A styles file left out is not touched, nor are the data without `data`. Only the files of
the chain are written, each one only if its text changes, whole (to a file beside it that then takes its place) and
with the line ends it had. The own styles and the sample data are created if they do not exist and their text is not
blank. The `.styles.stxt` of a folder is never created, and no file is deleted. The answer is that of the `GET`, with
the new `ETag` and the files written (`"written"`). Without `Save`, `PUT` is a `405`. A server that others reach
should have a token with `Save` on.

With `If-None-Match: *` instead of `If-Match`, the same `PUT` creates a new template or document: its `.stxt` and, if
the body has them, its own styles and its sample data, with LF. There must be none at that path (`412`), and its
folder has to exist (`404`). It answers `201`, as the `GET` of the new one. The lists of `/files/templates` and
`/files/documents` say whether the server creates (`"writable"`).

```bash
curl -X PUT -H 'Content-Type: application/json' -H 'If-None-Match: *' \
    --data-binary '{"document": "Document (dev.stxt.pdf): ..."}' \
    http://127.0.0.1:8080/files/documents/examples/poster
```

## Index and editor

`GET /` is the index. On the left, one tab for the documents, one for the templates and one for the routes of the API.
The first two show the tree of their folders and the entries of the folder chosen. A click on an entry shows its SVG
pages on the right, with the fonts of the server; **PDF** shows its PDF instead, and **Edit** opens it in the editor.
With `Save` on, **New document** and **New template** create one in the folder chosen, with a page that has its name as
title, and open it in the editor. What the index shows is in its URL (`/#documents/reference/01-values`).

The editor tries a document or a template against the server:

| URL | What it shows |
|---|---|
| `/editor` | A blank document |
| `/editor/documents/{path}` | The document `{path}`: `/editor/documents/examples/blue-hour` |
| `/editor/templates/{path}` | The template `{path}`: `/editor/templates/examples/catalog` |

It has one tab for each file: for a template, its sample data first, then the document and its chain of styles from
the nearest. **Preview** shows the SVG pages (with **Outlines**, the outlines of the boxes), **PDF** the PDF and, for a
template, **Fill** the filled document. Unchanged, a template is printed with `/print` and the data of its panel, and a
document with `/documents`; edited, either one with `/render` and the whole chain. For a template, the page shows the
URL of `/print` and a `curl` with the data of the panel.

An error of the server is marked on its line, in the tab of its file. The document is also checked in the page against
the schema of `/schema`, for a template only while its data panel is empty: a document with Slots is validated by the
server, once filled.

With `Save` on, **Save** (or Ctrl+S) writes the files that changed ([Saving](#saving)), and a `.styles.stxt` of a
folder, which applies to everything below it, only after a confirmation. Without `Save` there is no button, and the
changes are lost on leaving the page.

The pages and their files (`/assets/`) are served without token. With a token, the pages ask for it when the API
answers `401`, and keep it in the `sessionStorage` of the tab.

## Command line

The jar runs with `java -jar stxt-press.jar <command>`. The commands have the names of the routes:

| Command | What it does |
|---|---|
| `start [--config <file>]` | Starts the HTTP service with its `server.stxt` |
| `render <document> -o <file>` | Converts a layout document to PDF, with `--styles <file>` (one for each, in order), `--data <file>`, `--pdf-ua <1\|2>`, `--pdf-a <A2B\|A2A\|A3B\|A3A>`, `--exclude <check>` and `--debug` |
| `print <template> -o <file>` | Converts a template of the templates folder to PDF, with its sample data or those of `--data <file>`; the folder is that of `server.stxt` or `--templates <folder>` |
| `documents <document> -o <file>` | Converts a document of the documents folder to PDF; the folder is that of `server.stxt` or `--documents <folder>` |
| `fill <document> --data <file>` | Writes the document with its Slots filled, to stdout or to `-o <file>` |

From the root of the repository:

```bash
java -jar server/stxt-press.jar print examples/letter -o letter.pdf --config server/server.stxt
java -jar server/stxt-press.jar documents examples/blue-hour -o blue-hour.pdf --config server/server.stxt
java -jar server/stxt-press.jar render server/templates/examples/letter.stxt \
    --styles server/templates/examples/.styles.stxt --styles server/templates/examples/letter.styles.stxt \
    --data server/templates/examples/letter.json -o letter.pdf --config server/server.stxt
```

`render`, `print` and `documents` take the resources folder, the limits and the options from `server.stxt`, found as
for `start`. `render` takes only the styles given, so the `render` above gives the same PDF as the `print`. `-` is
stdin for a document or a data file, and stdout for `-o`. A file of `-o` is replaced only when the job ends well.
`java -jar stxt-press.jar --help` lists every option.

An error of a job is written to stderr with its file and its line:

```
stxt-press: server/templates/examples/invoice.stxt:11: SLOT_NO_DATA: Node 'Title': No data for ${number}
```

| Exit code | When |
|---|---|
| `0` | The job ended well |
| `1` | The job failed: the document, its styles, its data, a resource, a limit, a file that cannot be read or is not UTF-8 |
| `2` | The invocation is not valid: an option, a file that does not exist, a `server.stxt` that is not valid |

`start` warms up before answering (`Warm up` of `server.stxt`): it reads the whole resources folder and converts every
template with its sample data and every document, to PDF and to SVG, so that the first request is as fast as the next
ones. Until it ends, every route answers `503` (`SERVER_STARTING`). A template or a document that cannot be converted
is a warning in the log, and the server starts all the same.

`start` stops on `SIGTERM` or Ctrl+C. The conversions in progress have the `Timeout` of `server.stxt` to finish, and
new requests are answered with `503`. Without conversions in progress, it stops at once.

## Configuration

A server is configured with a `server.stxt` file:

```stxt
Server (dev.stxt.press):
    Host: 0.0.0.0
    Port: 8080
    Token env: STXT_PRESS_TOKEN
    Resource folder: resources
    Template folder: templates
    Document folder: documents
    Routes:
        Render: false
        Save: true
    Limits:
        Timeout: 20
        Max pages: 500
    Template options:
        PDF UA: 2
```

The file used is the first of: the path of `--config`, the path of the environment variable `STXT_PRESS_CONFIG`, and
`server.stxt` in the working directory. A path given by `--config` or `STXT_PRESS_CONFIG` that is not a file stops the
start. Without a file, the server listens on `127.0.0.1:8080`, without token, with the resources folder `resources` of
the working directory and without templates or documents. Relative paths are relative to the folder of `server.stxt`,
so a server moves with its configuration.

The file is validated with the schema `dev.stxt.press` of the jar
([`dev.stxt.press.stxt`](src/main/resources/stxt/dev.stxt.press.stxt), where each node is described). An error stops
the start with its code and its line: the codes of `stxt-core` for the syntax and the schema
(`NODE_NOT_DEFINED_IN_SCHEMA`, `INVALID_VALUE`...), and `CONFIG_*` for the rest (a port above 65535, a folder that
does not exist, `Resource folder` with `Font folder`...). A change in the file needs a restart.

| Node | Default | What it sets |
|---|---|---|
| `Host` | `127.0.0.1` | The address the server listens on |
| `Port` | `8080` | The port; `0` for any free one |
| `Token env` | none | The environment variable with the token, which has to be set and not empty when the server starts. The token is not written in the file |
| `Warm up` | `true` | Converting every template and every document once before answering |
| `Lazy init` | `false` | Opening the resources with the first request that needs them, instead of at the start. It turns `Warm up` off, and both `true` is an error |
| `Resource folder` | `resources` | The folder with `fonts/` and `images/` |
| `Font folder`, `Image folder` | none | The fonts and the images in folders of their own, instead of `Resource folder`; each `Image folder` with its `Prefix` |
| `Template folder` | none | The templates folder |
| `Document folder` | none | The documents folder |
| `Routes` | all on but `Save` | `Editor` (the index, the editor and `/fonts`), `Render`, `Files` (the routes under `/files/`) and `Save` ([Saving](#saving)) |
| `Limits` | | `Max body size` (bytes, 2 MiB), `Timeout` (seconds, 30), `Concurrent conversions` (4), and the limits of the engine: `Max pages`, `Max image pixels`, `Max SVG elements`, `Max SVG depth`, `Max PDF bytes` |
| `Template options` | | `PDF UA` (`1` or `2`), `PDF A` (`A2B`, `A2A`, `A3B`, `A3A`) and one `Exclude` for each check not made, as the `options` of `/render`; for the templates and the documents of the server |
| `Producer` | `stxt-press 0.7.0 (stxt-pdf 0.19.0)` | The producer that the PDFs of the server and of `render`, `print` and `documents` name |

The `server.stxt` of `server/` listens on `127.0.0.1:8080`, turns `Save` on, for the editor of the same machine, and
shows the other nodes commented with their defaults.

## Build

Requires **Java 17** or later, and [stxt-pdf](https://github.com/stxt-lang/stxt-pdf) checked out next to this
repository (`../stxt-pdf`): the tests use its fonts and its examples.

```bash
mvn verify
```

The editor is in `web/` (TypeScript, bundled with esbuild). Maven installs Node in `target/`, runs `npm install` and
`npm run build`, which leaves the page in `target/classes/editor`, and runs `npm test` after the Java tests.
`-Dnpm.install=ci` installs exactly the dependencies of `web/package-lock.json`.

`target/stxt-press-<version>.jar` is a single jar with every dependency inside, the one that runs with `java -jar`, and
`mvn package` copies it to `server/stxt-press.jar`. `target/original-stxt-press-<version>.jar` has only the classes of
the service.

| Dependency | Version |
|---|---|
| [`dev.stxt:stxt-core`](https://github.com/stxt-lang/stxt-java) | 1.0.4 |
| [`dev.stxt:stxt-pdf`](https://github.com/stxt-lang/stxt-pdf) | 0.19.0 |
| [`dev.stxt:stxt-transform`](https://github.com/stxt-lang/stxt-java-transform) | 0.2.0 |
| [`tools.jackson.core:jackson-databind`](https://github.com/FasterXML/jackson-databind) | 3.3.0 |

## License

[MIT](LICENSE), copyright `stxt-lang`.
