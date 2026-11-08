# stxt-press

PDF and SVG generation service for [STXT](https://stxt.dev) layout documents (`dev.stxt.pdf`). The layout, the PDF
and the SVG pages are the work of [stxt-pdf](https://github.com/stxt-lang/stxt-pdf), and the fill of Slots with JSON data,
of [stxt-transform](https://github.com/stxt-lang/stxt-java-transform): this project does not parse, validate or lay out
anything itself.

In development: no version has been released, and the server has no command to start it yet (it is started from Java,
with `ServerFile.load(...).start()`).

## Routes

| Route | What it does |
|---|---|
| `GET /print/{path}` | The template `{path}` of the templates folder, filled with its sample data |
| `POST /print/{path}` | The template `{path}`, filled with the body: the JSON data as they are |
| `POST /render` | Any document: `{"document": "...", "styles": ["..."], "data": {...}, "options": {...}}` |
| `GET /templates` | The templates, with the files each one has |
| `GET /templates/{path}` | The document, the chain of styles and the sample data of a template |
| `GET /schema` | The schema `dev.stxt.pdf` of the engine |
| `GET /fonts` | The font families of the server |
| `GET /health` | `{"status": "ok"}`, without token |

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
document and `/templates` shows the texts and the sample data of the templates, so both can be turned off, leaving
`/print`, `/schema`, `/fonts` and `/health`.

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

The file used is the first of: the path of `--config`, the path of the environment variable `STXT_PRESS_CONFIG`, and
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
| `Routes` | all on | `Render` and `Templates`: `/render` and `/templates` on or off |
| `Limits` | — | `Max body size` (bytes, 2 MiB), `Timeout` (seconds, 30), `Concurrent conversions` (4), and the limits of the engine: `Max pages`, `Max image pixels`, `Max SVG elements`, `Max SVG depth`, `Max PDF bytes` |
| `Template options` | — | `PDF UA` (`1` or `2`), `PDF A` (`A2B`, `A2A`, `A3B`, `A3A`) and one `Exclude` for each check not made, as the `options` of `/render` |

Relative paths are relative to the folder of `server.stxt`, so a server moves with its configuration. The token is not
written in the file, only the name of its variable.

## Build

Requires **Java 17** or later.

```bash
mvn verify
```

| Dependency | Version |
|---|---|
| [`dev.stxt:stxt-core`](https://github.com/stxt-lang/stxt-java) | 1.0.3 |
| [`dev.stxt:stxt-pdf`](https://github.com/stxt-lang/stxt-pdf) | 0.13.0-SNAPSHOT: the working tree of `../stxt-pdf`, installed with `mvn install` there, until 0.13.0 is published |
| [`dev.stxt:stxt-transform`](https://github.com/stxt-lang/stxt-java-transform) | 0.1.0 |
| [`com.fasterxml.jackson.core:jackson-databind`](https://github.com/FasterXML/jackson-databind) | 2.22.4 |

## License

[MIT](LICENSE), copyright `stxt-lang`.
