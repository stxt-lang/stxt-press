# stxt-press

PDF and SVG generation service for [STXT](https://stxt.dev) layout documents (`dev.stxt.pdf`). The layout, the PDF
and the SVG pages are the work of [stxt-pdf](https://github.com/stxt-lang/stxt-pdf), and the fill of Slots with JSON data,
of [stxt-transform](https://github.com/stxt-lang/stxt-java-transform): this project does not parse, validate or lay out
anything itself.

In development: no version has been released.

```bash
server/start.sh
java -jar stxt-press.jar render invoice.stxt --styles styles.stxt --data invoice.json -o invoice.pdf
```

## Routes

| Route | What it does |
|---|---|
| `GET /print/templates/{path}` | The template `{path}` of the templates folder, filled with its sample data |
| `POST /print/templates/{path}` | The template `{path}`, filled with the body: the JSON data as they are |
| `GET /print/documents/{path}` | The document `{path}` of the documents folder, as it is |
| `POST /render` | Any document: `{"document": "...", "styles": ["..."], "data": {...}, "options": {...}}` |
| `GET /templates` | The templates, with the files each one has |
| `GET /templates/{path}` | The document, the chain of styles and the sample data of a template |
| `GET /documents` | The documents, with the files each one has |
| `GET /documents/{path}` | The document and the chain of styles of a document |
| `PUT /templates/{path}`, `PUT /documents/{path}` | Writes the files of a template or a document, with [`Save`](#saving) on |
| `GET /schema` | The schema `dev.stxt.pdf` of the engine |
| `GET /fonts` | The font families of the server, and each face (family, weight, style) with the id of its file; for the editor |
| `GET /fonts/{id}` | The file of a face, by its SHA-256; for the editor |
| `GET /health` | `{"status": "ok", "versions": {"stxt-press": "0.1.0", "stxt-pdf": "0.17.0"}}`, without token |
| `GET /` | The [index](#index-and-editor): the templates, the documents and the routes, without token |
| `GET /editor`, `GET /editor/templates/{path}`, `GET /editor/documents/{path}` | The [web editor](#index-and-editor), blank, on a template or on a document, without token |

`/print` and `/render` answer with a PDF (`?format=pdf`, the default), one SVG per page (`?format=svg`, as
`{"pages": [...]}`, with `&debug=true` for the outlines of the boxes) or the filled document (`?format=stxt`).
Every PDF names its producer in its document information and its XMP: `stxt-press 0.1.0 (stxt-pdf 0.17.0)`, or the
`Producer` of `server.stxt`.

```bash
curl -o invoice.pdf http://127.0.0.1:8082/print/templates/invoices/invoice
curl -o invoice.pdf -d @data.json http://127.0.0.1:8082/print/templates/invoices/invoice
curl -o blue-hour.pdf http://127.0.0.1:8082/print/documents/examples/poster/blue-hour
```

A template is a `<name>.stxt` of the templates folder, named by its path without extension
(`invoices/invoice` is `invoices/invoice.stxt`). Beside it, all optional: `<name>.styles.stxt`, its styles;
`<name>.json`, its sample data; and a `.styles.stxt` in any folder above it, for the templates below. The styles apply
from the root to the template, and the nearest one wins.

A document of the documents folder follows the same rules, without data: it is printed as it is, with its chain of
styles, and a `<name>.json` beside it is not read. Its errors are `DOCUMENT_NOT_FOUND` and `DOCUMENT_LINK_OUTSIDE`.

An error is a JSON body with its `code` and its `message`:

| Status | When |
|---|---|
| `400` | The body or a parameter is not valid (`BODY_NOT_VALID`, `PARAMETER_NOT_VALID`) |
| `401` | The server has a token and the request does not carry it (`Authorization: Bearer <token>`) |
| `403` | The server has no token, listens on a loopback address and the `Host` of the request is not a local name (`HOST_NOT_ALLOWED`) |
| `404` | The route, the template or the document does not exist (`NOT_FOUND`, `TEMPLATE_NOT_FOUND`, `DOCUMENT_NOT_FOUND`) |
| `405` | The method is not one of the route, which the `Allow` header lists |
| `409` | A save from a version that is not that of the files any more (`FILES_CHANGED`) |
| `413` | The body is larger than the server accepts (`BODY_TOO_LARGE`), or the document exceeds a limit of the engine |
| `422` | The document, its styles or its data are not valid |
| `503` | The conversion ran out of time (`CANCELLED`), the server is converting as many as it can or stopping (`SERVER_BUSY`), or it is warming up (`SERVER_STARTING`, `/health` too) |
| `500` | An error of the server: its resources, its templates or documents folder, the engine |

The errors of a conversion (`413`, `422`, `503`) also carry `kind`, `line` and `source`, the input the line belongs to:
`document`, `data`, `filled` (the document after filling its Slots, which the body carries in `filled`), the place of a
styles document in `/render` (`styles[1]`) or the path of its file in a template or a document
(`invoices/.styles.stxt`).

```json
{"kind": "INVALID_DOCUMENT", "code": "JSON_NOT_VALID", "line": 2, "source": "data", "message": "..."}
```

The server has no TLS or users: it is meant to run behind a reverse proxy that adds them. `start` limits the time of
each request, so that a client that sends a request by halves does not keep a thread of the server: the request, its
headers and its body, has 30 seconds to arrive whole, and its response the `Timeout` of `server.stxt` and 30 seconds
more to be sent whole; otherwise the connection is closed. They are the properties `sun.net.httpserver.maxReqTime`
and `sun.net.httpserver.maxRspTime` of the JDK, in seconds, and a value given with `-D` wins:

```bash
java -Dsun.net.httpserver.maxReqTime=120 -jar stxt-press.jar start
```

A server without token that listens on a loopback address, as the default `127.0.0.1`, answers only to requests whose
`Host` is `localhost`, `127.0.0.1`, `[::1]` or the `Host` of `server.stxt`, with any port; any other, a missing one
included, gets `403` (`HOST_NOT_ALLOWED`), on every route. A page of another site open in the browser could otherwise
make its own name resolve to `127.0.0.1` (DNS rebinding) and read the templates or use `/render` as if it were of the
same origin. A server reached through another name, such as an alias of the `hosts` file, needs a token (`Token env`)
or that name in `Host`. With a token, or on another address, any `Host` is answered.

`/render` converts any document, `/templates` shows the texts and the sample data of the templates and `/documents`
the texts of the documents, so the three can be turned off. `/fonts` gives the files of the fonts to the SVG previews
of the editor, and is turned off with it, as are `/`, `/editor` and `/assets/`, the files of the pages. With
`/render`, `/templates`, `/documents` and the editor off, the server has `/print`, `/schema` and `/health`.

### Saving

With `Save: true` in the `Routes` of `server.stxt` (off by default), `PUT /templates/{path}` and
`PUT /documents/{path}` write the files of a template or a document: the Save of the editor. The body has the shape of
the `GET`, with the texts to write; `If-Match` has the `ETag` of the `GET` they come from, and a save from another
version writes nothing, so that it does not undo a change made since, on disk or by someone else (`409`,
`FILES_CHANGED`).

```bash
curl -X PUT -H 'Content-Type: application/json' -H 'If-Match: "<the ETag of the GET>"' \
    --data-binary '{"document": "Document (dev.stxt.pdf): ...", "styles": [{"path": "invoices/invoice.styles.stxt", "text": "..."}], "data": "{...}"}' \
    http://127.0.0.1:8082/templates/invoices/invoice
```

Only `document` is required; a styles file left out is not touched, nor are the data without `data`. Only the files
of the chain of the template or the document are written, each one only if its text changes, whole (to a file beside
it that then takes its place) and with the line ends it had. The own styles (`<name>.styles.stxt`) and the sample data
(`<name>.json`) are created if they do not exist and their text is not blank; the `.styles.stxt` of a folder is never
created, and no file is deleted. The answer is that of the `GET`, with the new `ETag` and the files written
(`"written"`). A save needs `Content-Type: application/json` (`415`, `CONTENT_TYPE_NOT_VALID`) and `If-Match` (`428`,
`VERSION_REQUIRED`). Without `Save`, `PUT` is a `405`. A server that others reach should have a token with `Save` on.

## Index and editor

`GET /` is the index, which fills the window in two halves, as the editor. On the left, a tab for the documents (the
one it opens with), one for the templates and one for the routes of the API, and a link to a new document. The
documents and the templates tabs show the tree of their folders, with how many entries each one has, and the entries
of the folder chosen and of those below it, each with a link to the editor and a **PDF** button. On the right, the PDF
of the last **PDF** button, in the viewer of the browser, with links to edit it and to download it with its name; the
entry it is of is marked in its list. Choosing a folder leaves only its branch open. What the index shows is in its
hash (`/#documents/reference/01-values`), and the name of the editor goes back to the folder of what it shows.

The editor is a page to try a document or a template against the server. It is opened from the index, where another
one is chosen: its name in the bar goes back to the folder of what it shows. What it shows is in its URL, so a reload
shows it again, reading it from the server, which reads its folders on every request: a template or a document edited
on disk is seen with a reload.

| URL | What it shows |
|---|---|
| `/editor` | A blank document |
| `/editor/documents/{path}` | The document `{path}` of the documents folder: `/editor/documents/examples/poster/blue-hour` |
| `/editor/templates/{path}` | The template `{path}` of the templates folder: `/editor/templates/catalogs/catalog` |

With changes in the panels, leaving the page (the index, back, a reload) asks first. With `Save` on in the server,
**Save** (or Ctrl+S) writes the files that changed ([Saving](#saving)); a `.styles.stxt` of a folder, which applies to
everything below it, is saved only after a confirmation. Without `Save`, there is no button: what the panels change is
tried with the buttons and lost on leaving.

- **A document**: the document and its chain of styles, without data, one panel per file. As the server has them, it
  is printed with `/print/documents`; edited, or a blank one, with `/render`.
- **A template**: its sample data first, the panel that changes from one PDF to another, then the document and its
  chain of styles, one panel per file. With the document and the styles as the server has them, it is printed with
  `/print/templates` and the data of the panel; edited, with `/render` and the whole chain. The page shows the URL of
  `/print/templates` and a `curl` with the data of the panel.

The editor fills the window. The bar has the path of what it shows and the buttons; on the left, one tab for each
file (the data, the document, each styles document), with a dot when the file has an error (red) or a warning of the
schema (amber); on the right, the output of the last button. **Preview** shows the SVG pages (with **Outlines**, the
outlines of the boxes) and **PDF** the PDF in the viewer of the browser, with a link that downloads it with its name;
for a template, **Fill** shows the filled document. Each one says how long it took. Each half has a button, at its
bottom right, that puts it over the window, under the bar, which keeps its buttons and gets a cross that puts it back,
as Esc does; the PDF of the index has it too. An
error of the server is marked in its panel, on its line, and its tab is shown. The document panel validates against
the schema of `/schema`, for a template only while the data panel is empty: a document with Slots is validated
by the server, once filled. A window narrower than 900 px shows one column, and the page scrolls.

The pages and their files (`/assets/`) are served without token, the same page for every `{path}`, whether the template
or the document exists or not. With a token, the pages ask for it when the API answers `401`, and keep it in the `sessionStorage` of
the tab.

## Command line

The jar runs with `java -jar stxt-press.jar <command>`. The commands have the names of the routes:

| Command | What it does |
|---|---|
| `start [--config <file>]` | Starts the HTTP service with its `server.stxt` |
| `render <document> -o <file>` | Converts a layout document to PDF, with `--styles <file>` (one for each, in order), `--data <file>`, `--pdf-ua <1\|2>`, `--pdf-a <A2B\|A2A\|A3B\|A3A>`, `--exclude <check>` and `--debug` |
| `print <template> -o <file>` | Converts a template of the templates folder to PDF, with its sample data or those of `--data <file>`; the folder is that of `server.stxt` or `--templates <folder>` |
| `fill <document> --data <file>` | Writes the document with its Slots filled, to stdout or to `-o <file>` |

`render` and `print` take the resources folder, the limits and the options of the templates from `server.stxt`,
found as for `start` (`--config`, `STXT_PRESS_CONFIG`, `./server.stxt`). `-` is stdin for a document or a data file,
and stdout for `-o`. A file of `-o` is replaced only when the job ends well. `java -jar stxt-press.jar --help` lists
every option.

An error of a job is written to stderr with its file and its line:

```
stxt-press: server/templates/invoices/invoice.stxt:73: SLOT_NO_DATA: Node 'Block': No data for {customer}
```

| Exit code | When |
|---|---|
| `0` | The job ended well |
| `1` | The job failed: the document, its styles, its data, a resource, a limit |
| `2` | The invocation is not valid: an option, a file that does not exist, a `server.stxt` that is not valid |

`start` warms up before answering (`Warm up` of `server.stxt`): it reads the whole resources folder and converts every
template with its sample data and every document, to PDF and to SVG, so that the first request is as fast as the next
ones. It listens from the start, and until it ends every route, `/health` too, answers `503` (`SERVER_STARTING`, with
`Retry-After`). A template or a document that cannot be converted is a warning in the log, and the server starts all
the same.

`start` stops on `SIGTERM` or Ctrl+C, giving the conversions in progress the `Timeout` of `server.stxt` to finish;
without any it stops at once. While it waits, new requests are answered with `503`.

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

The file used is the first of: the path of `--config` of the command line, the path of the environment variable
`STXT_PRESS_CONFIG`, and
`server.stxt` in the working directory. A path given by `--config` or `STXT_PRESS_CONFIG` that is not a file stops the
start. Without a file, the server listens only on `127.0.0.1:8080`, without token, with the resources folder
`resources` of the working directory and without templates or documents.

The file is validated with the schema `dev.stxt.press` of the jar (`src/main/resources/stxt/dev.stxt.press.stxt`,
where each node is described). An error stops the start with its code and its line: the codes of `stxt-core` for the
syntax and the schema (`NODE_NOT_DEFINED_IN_SCHEMA`, `INVALID_VALUE`…), and `CONFIG_*` for the rest (a port above 65535,
a folder that does not exist, `Resource folder` with `Font folder`…). A change in the file needs a restart.

| Node | Default | What it sets |
|---|---|---|
| `Host` | `127.0.0.1` | The address the server listens on |
| `Port` | `8080` | The port; `0` for any free one |
| `Token env` | none | The environment variable with the token. It has to be set and not empty when the server starts |
| `Warm up` | `true` | Reading the whole resources folder and converting every template and every document once before answering; until then every route answers `503` |
| `Lazy init` | `false` | Opening the resources with the first request that needs them, instead of at the start; it turns `Warm up` off, and both `true` is an error |
| `Resource folder` | `resources` | The folder with `fonts/` and `images/` |
| `Font folder`, `Image folder` | — | The fonts and the images in folders of their own, instead of `Resource folder`; each `Image folder` with its `Prefix` |
| `Template folder` | none | The templates folder |
| `Document folder` | none | The documents folder |
| `Routes` | all on but `Save` | `Editor`, `Render`, `Templates` and `Documents`: the index and the editor (with `/fonts`), `/render`, `/templates` and `/documents` on or off; `Save`, the `PUT` of `/templates` and `/documents` ([Saving](#saving)), off by default |
| `Limits` | — | `Max body size` (bytes, 2 MiB), `Timeout` (seconds, 30), `Concurrent conversions` (4), and the limits of the engine: `Max pages`, `Max image pixels`, `Max SVG elements`, `Max SVG depth`, `Max PDF bytes` |
| `Template options` | — | `PDF UA` (`1` or `2`), `PDF A` (`A2B`, `A2A`, `A3B`, `A3A`) and one `Exclude` for each check not made, as the `options` of `/render`; for the templates and the documents of `/print` |
| `Producer` | `stxt-press 0.1.0 (stxt-pdf 0.17.0)` | The producer that the PDFs of the server and of `render` and `print` name |

Relative paths are relative to the folder of `server.stxt`, so a server moves with its configuration. The token is not
written in the file, only the name of its variable.

The folder [`server/`](server/) is a whole server, to copy anywhere and to start a project from: `start.sh` and
`start.bat`, which start it; `stxt-press.jar`, which `mvn package` copies there; its `server.stxt`, which listens on
`127.0.0.1:8082`, turns `Save` on (for the editor of this machine) and `Warm up` off (it answers at once), and shows the other nodes commented with their
defaults; the fonts IBM Plex Sans, Serif and Mono and
the images of the examples of stxt-pdf in `resources/`; and in `templates/`, one template for each example of
stxt-pdf (`invoices/invoice`, `letters/letter`, `resumes/resume`, `reports/annual-report`, `newsletters/newsletter`,
`menus/menu`, `contracts/contract`, `manuals/manual`, `catalogs/catalog`, `certificates/certificate` and
`timetables/timetable`), with Slots for what changes from one document to another, its styles and its sample data,
which give the example; and in `documents/`, documents printed as they are: complete examples of other kinds of
documents in `documents/examples/`, and in `documents/reference/` a reference of the layout language, one small
document for each use, with a line that says what it shows. Its `.stxt/` has the schemas `dev.stxt.pdf` and `dev.stxt.press`, so that an editor
with discovery (VS Code with the STXT extension) checks and completes every file of the folder. `server/start.sh`
starts it, from any working directory, and
`java -jar server/stxt-press.jar print invoices/invoice -o invoice.pdf --config server/server.stxt` prints its template.

## Build

Requires **Java 17** or later, and [stxt-pdf](https://github.com/stxt-lang/stxt-pdf) checked out next to this
repository (`../stxt-pdf`): the tests use its fonts and its examples.

```bash
mvn verify
```

The editor is in `web/` (TypeScript, bundled with esbuild). Maven installs Node in `target/`, runs `npm install` and
`npm run build`, which leaves the page in `target/classes/editor`, and runs `npm test` after the Java tests.

`target/stxt-press-<version>.jar` is a single jar with every dependency inside, the one that runs with `java -jar`;
`target/original-stxt-press-<version>.jar` has only the classes of the service.

| Dependency | Version |
|---|---|
| [`dev.stxt:stxt-core`](https://github.com/stxt-lang/stxt-java) | 1.0.3 |
| [`dev.stxt:stxt-pdf`](https://github.com/stxt-lang/stxt-pdf) | 0.17.0 |
| [`dev.stxt:stxt-transform`](https://github.com/stxt-lang/stxt-java-transform) | 0.1.0 |
| [`com.fasterxml.jackson.core:jackson-databind`](https://github.com/FasterXML/jackson-databind) | 2.22.4 |

## License

[MIT](LICENSE), copyright `stxt-lang`.
