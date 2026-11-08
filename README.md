# stxt-press

PDF and SVG generation service for [STXT](https://stxt.dev) layout documents (`dev.stxt.pdf`). The layout, the PDF
and the SVG pages are the work of [stxt-pdf](https://github.com/stxt-lang/stxt-pdf), and the fill of Slots with JSON data,
of [stxt-transform](https://github.com/stxt-lang/stxt-java-transform): this project does not parse, validate or lay out
anything itself.

In development: no version has been released, and the server has no command to start it yet (it is started from Java,
with `PressServer.start`).

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
