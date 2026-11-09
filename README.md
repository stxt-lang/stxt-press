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
| `GET /print/{path}` | The template `{path}` of the templates folder, filled with its sample data |
| `POST /print/{path}` | The template `{path}`, filled with the body: the JSON data as they are |
| `POST /render` | Any document: `{"document": "...", "styles": ["..."], "data": {...}, "options": {...}}` |
| `GET /templates` | The templates, with the files each one has |
| `GET /templates/{path}` | The document, the chain of styles and the sample data of a template |
| `GET /schema` | The schema `dev.stxt.pdf` of the engine |
| `GET /fonts` | The font families of the server, and each face (family, weight, style) with the id of its file; for the editor |
| `GET /fonts/{id}` | The file of a face, by its SHA-256; for the editor |
| `GET /health` | `{"status": "ok", "versions": {"stxt-press": "0.1.0", "stxt-pdf": "0.13.0"}}`, without token |
| `GET /` | The [index](#index-and-editor): the templates and the routes, without token |
| `GET /editor`, `GET /editor/{path}` | The [web editor](#index-and-editor), blank or on a template, without token |

`/print` and `/render` answer with a PDF (`?format=pdf`, the default), one SVG per page (`?format=svg`, as
`{"pages": [...]}`, with `&debug=true` for the outlines of the boxes) or the filled document (`?format=stxt`).

```bash
curl -o invoice.pdf http://127.0.0.1:8080/print/invoices/invoice
curl -o invoice.pdf -d @data.json http://127.0.0.1:8080/print/invoices/invoice
```

A template is a `<name>.stxt` of the templates folder, named by its path without extension
(`invoices/invoice` is `invoices/invoice.stxt`). Beside it, all optional: `<name>.styles.stxt`, its styles;
`<name>.json`, its sample data; and a `.styles.stxt` in any folder above it, for the templates below. The styles apply
from the root to the template, and the nearest one wins.

An error is a JSON body with its `code` and its `message`:

| Status | When |
|---|---|
| `400` | The body or a parameter is not valid (`BODY_NOT_VALID`, `PARAMETER_NOT_VALID`) |
| `401` | The server has a token and the request does not carry it (`Authorization: Bearer <token>`) |
| `404` | The route or the template does not exist (`NOT_FOUND`, `TEMPLATE_NOT_FOUND`) |
| `405` | The method is not one of the route, which the `Allow` header lists |
| `413` | The body is larger than the server accepts (`BODY_TOO_LARGE`), or the document exceeds a limit of the engine |
| `422` | The document, its styles or its data are not valid |
| `503` | The conversion ran out of time (`CANCELLED`), or the server is converting as many as it can (`SERVER_BUSY`) |
| `500` | An error of the server: its resources, its templates folder, the engine |

The errors of a conversion (`413`, `422`, `503`) also carry `kind`, `line` and `source`, the input the line belongs to:
`document`, `data`, `filled` (the document after filling its Slots, which the body carries in `filled`), the place of a
styles document in `/render` (`styles[1]`) or the path of its file in a template (`invoices/.styles.stxt`).

```json
{"kind": "INVALID_DOCUMENT", "code": "JSON_NOT_VALID", "line": 2, "source": "data", "message": "..."}
```

The server has no TLS or users: it is meant to run behind a reverse proxy that adds them. `/render` converts any
document and `/templates` shows the texts and the sample data of the templates, so both can be turned off. `/fonts`
gives the files of the fonts to the SVG previews of the editor, and is turned off with it, as are `/`, `/editor` and
`/assets/`, the files of the pages. With `/render`,
`/templates` and the editor off, the server has `/print`, `/schema` and `/health`.

## Index and editor

`GET /` is the index: the templates of the server by folder, each with a link to the editor, a link to it as a
document and a button that opens its PDF; a link to a new document; and the routes of the API.

The editor is a page to try documents and templates against the server. What it shows is in its URL, so a reload shows
it again, reading the template from the server, which reads its folder on every request: a template edited on disk is
seen with a reload.

| URL | What it shows |
|---|---|
| `/editor` | A blank document |
| `/editor/{path}` | The template `{path}` in the templates mode: `/editor/catalogs/catalog` |
| `/editor/{path}?mode=document` | The template `{path}` as a document |

The list of templates and the mode buttons change the URL, in the history of the browser. With changes in the panels,
leaving them (another template, back, a reload) asks first. The two modes:


- **Document**: a layout document and its styles, sent to `/render` as they are, without data. The list has the
  templates of the server, each one filled with its sample data and with its chain of styles, one panel per file.
- **Templates**: a template of the server: its sample data first, the panel that changes from one PDF to another, then
  the document and its chain of styles, one panel per file. With the document and the styles as the server has them,
  it is printed with `/print` and the data of the panel; edited, with `/render` and the whole chain. Nothing is saved
  on the server. The page shows the URL of `/print` and a `curl` with the data of the panel.

**Preview** shows the SVG pages (with **Outlines**, the outlines of the boxes) and **Download PDF** the PDF; in the
templates mode, **Fill** shows the filled document. An error of the server is marked in its panel, on its line. The
document panel validates against the schema of `/schema`, in the templates mode only while the data panel is empty: a
document with Slots is validated by the server, once filled.

The pages and their files (`/assets/`) are served without token, the same page for every `{path}`, whether the template
exists or not. With a token, the pages ask for it when the API answers `401`, and keep it in the `sessionStorage` of
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
    Routes:
        Render: false
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
`resources` of the working directory and without templates.

The file is validated with the schema `dev.stxt.press` of the jar (`src/main/resources/stxt/dev.stxt.press.stxt`,
where each node is described). An error stops the start with its code and its line: the codes of `stxt-core` for the
syntax and the schema (`NODE_NOT_DEFINED_IN_SCHEMA`, `INVALID_VALUE`…), and `CONFIG_*` for the rest (a port above 65535,
a folder that does not exist, `Resource folder` with `Font folder`…). A change in the file needs a restart.

| Node | Default | What it sets |
|---|---|---|
| `Host` | `127.0.0.1` | The address the server listens on |
| `Port` | `8080` | The port; `0` for any free one |
| `Token env` | none | The environment variable with the token. It has to be set and not empty when the server starts |
| `Lazy init` | `false` | Opening the resources with the first request that needs them, instead of at the start |
| `Preload resources` | `false` | Reading the whole resources folder when it is opened, instead of each file when a document uses it |
| `Resource folder` | `resources` | The folder with `fonts/` and `images/` |
| `Font folder`, `Image folder` | — | The fonts and the images in folders of their own, instead of `Resource folder`; each `Image folder` with its `Prefix` |
| `Template folder` | none | The templates folder |
| `Routes` | all on | `Editor`, `Render` and `Templates`: the index and the editor (with `/fonts`), `/render` and `/templates` on or off |
| `Limits` | — | `Max body size` (bytes, 2 MiB), `Timeout` (seconds, 30), `Concurrent conversions` (4), and the limits of the engine: `Max pages`, `Max image pixels`, `Max SVG elements`, `Max SVG depth`, `Max PDF bytes` |
| `Template options` | — | `PDF UA` (`1` or `2`), `PDF A` (`A2B`, `A2A`, `A3B`, `A3A`) and one `Exclude` for each check not made, as the `options` of `/render` |

Relative paths are relative to the folder of `server.stxt`, so a server moves with its configuration. The token is not
written in the file, only the name of its variable.

The folder [`server/`](server/) is a whole server, to copy anywhere and to start a project from: `start.sh` and
`start.bat`, which start it; `stxt-press.jar`, which `mvn package` copies there; its `server.stxt`, which listens on
`127.0.0.1:8080` and shows the other nodes commented with their defaults; the fonts IBM Plex Sans, Serif and Mono and
the images of the examples of stxt-pdf in `resources/`; and in `templates/`, one template for each example of
stxt-pdf (`invoices/invoice`, `letters/letter`, `resumes/resume`, `reports/annual-report`, `newsletters/newsletter`,
`menus/menu`, `contracts/contract`, `manuals/manual`, `catalogs/catalog`, `certificates/certificate` and
`timetables/timetable`), with Slots for what changes from one document to another, its styles and its sample data,
which give the example. Its `.stxt/` has the schemas `dev.stxt.pdf` and `dev.stxt.press`, so that an editor
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
| [`dev.stxt:stxt-pdf`](https://github.com/stxt-lang/stxt-pdf) | 0.13.0 |
| [`dev.stxt:stxt-transform`](https://github.com/stxt-lang/stxt-java-transform) | 0.1.0 |
| [`com.fasterxml.jackson.core:jackson-databind`](https://github.com/FasterXML/jackson-databind) | 2.22.4 |

## License

[MIT](LICENSE), copyright `stxt-lang`.
