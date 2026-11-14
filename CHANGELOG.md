# Change Log

All notable changes to `stxt-press` are documented in this file, newest first.

The format follows [Keep a Changelog](http://keepachangelog.com/).

## [0.5.0] - 2026-11-14

The first version: a service that makes PDF and SVG from layout documents (`dev.stxt.pdf`), with the Slots filled with
JSON data, on `dev.stxt:stxt-pdf` 0.18.0, `dev.stxt:stxt-transform` 0.1.0 and `dev.stxt:stxt-core` 1.0.3. Java 17.
It runs as `java -jar stxt-press.jar start` and is configured with `server.stxt`.

### Added

- **The core**, in `dev.stxt.press`, without HTTP: `Press`, on the shared `Converter` of the server, with three jobs:
  `pdf` (to an `OutputStream`, written only once the document is laid out, so an error leaves it untouched), `svg`
  (one SVG per page) and the static `fill` (the filled document as STXT text). A `Job` carries the layout document,
  its separate styles documents in order, the JSON data and the `Options` (`pdfUa`, `pdfA`, `debug`, the excluded
  `Check`s); with data, the Slots are filled before converting. `Press.filled(Job)` is the text a job converts.
  `Press.producer()` names the service and the engine (`stxt-press 0.5.0 (stxt-pdf 0.18.0)`) in the document
  information and the XMP of every PDF.
- **The errors of a job**: a `PressException` with `kind`, `code`, `line` and `source`, the input the line belongs to
  (`document`, `data`, the name of a styles document, or `filled` for the filled document, whose text it carries,
  since its lines are not those of the document with Slots). `describe` writes it as one line,
  `where:line: CODE: message`.
- **The templates folder**, in `dev.stxt.press.templates`: a template is a `<name>.stxt`, named by its path without
  extension (`invoices/2026/invoice`), with its own `<name>.styles.stxt` and its sample data `<name>.json` beside it,
  all optional, and a `.styles.stxt` in any folder above it. The styles are chained from the root to the template,
  and the nearest one wins. Each segment of a path is `[A-Za-z0-9_-]+`; any other path is `TEMPLATE_NOT_FOUND`, and a
  file that is a symbolic link leaving the folder is not read (`TEMPLATE_LINK_OUTSIDE`). The folder is read on every
  call, so a changed template counts without restarting.
- **The documents folder**, with the same rules and without data: layout documents printed as they are, whose errors
  are `DOCUMENT_NOT_FOUND` and `DOCUMENT_LINK_OUTSIDE`. `Templates` reads either kind of folder (`Templates.Kind`).
- **The HTTP service**, in `dev.stxt.press.http`, on the `HttpServer` of the JDK, with `jackson-databind` 2.22.4 for
  the JSON. For the clients: `GET` and `POST /print/{path}` (a template, filled with its sample data or with the JSON
  of the body), `GET /documents/{path}` (a document of the documents folder), `POST /render` (any document, with its
  styles, its data as a JSON value and its options), `GET /schema` and `GET /health` (with the versions of the service
  and of the engine). `?format=pdf|svg|stxt` and `&debug=true` on `/print`, `/documents` and `/render`. For the
  editor: `GET /files/templates` and `GET /files/documents` (the lists), `GET /files/templates/{path}` and
  `GET /files/documents/{path}` (the texts, with an `ETag`), and `GET /fonts` and `GET /fonts/{id}` (the faces of the
  fonts and their files, for the SVG previews).
- **The HTTP errors**: JSON with `code` and `message`, and `kind`, `line`, `source` and `filled` for those of a job.
  `400`, `401`, `403`, `404`, `405`, `409`, `412`, `413`, `415`, `422`, `428`, `503` (a conversion out of time,
  `CANCELLED`; the server busy, `SERVER_BUSY`, with `Retry-After`; or warming up, `SERVER_STARTING`) and `500`, whose
  message has no details and whose error goes to the log.
- **Security**: with a token, every route but `/health` and the pages asks for `Authorization: Bearer`, compared in
  constant time, before the route is looked up. A server without token on a loopback address answers only to the
  `Host` `localhost`, `127.0.0.1`, `[::1]` or that of `server.stxt` (`403`, `HOST_NOT_ALLOWED`), against DNS
  rebinding. A request has 30 s to arrive whole, and its response the `Timeout` of `server.stxt` and 30 s more
  (`sun.net.httpserver.maxReqTime` and `maxRspTime` of the JDK, set by `start` unless given with `-D`). The body has a
  maximum size, with and without `Content-Length`. `X-Content-Type-Options: nosniff` on every response, and a
  `Content-Security-Policy` on the pages.
- **The save and the creation** of the templates and the documents, with `Save: true` in the `Routes` of
  `server.stxt` (off by default): `PUT /files/templates/{path}` and `PUT /files/documents/{path}` write the files of
  the body if `If-Match` has the version they are at (`409`, `FILES_CHANGED`, from another version); only the files
  of the chain whose text changes, each one whole (`AtomicFile`) and with the line ends it had, and nothing is
  deleted. With `If-None-Match: *` instead, they create a new one in a folder that exists (`201`, or `412` with
  `TEMPLATE_EXISTS` or `DOCUMENT_EXISTS`). A save needs `Content-Type: application/json` (`415`) and a version (`428`).
- **The warm up** (`Warm up` in `server.stxt`, on by default): before answering, the server reads the whole resources
  folder and converts every template with its sample data and every document, to PDF and to SVG, so that the first
  request is as fast as the next ones. It listens from the start and answers `503` (`SERVER_STARTING`) until it ends.
  A template or a document that cannot be converted is a warning in the log with its file and line, or only
  information for a template without sample data, and the server starts all the same. `Lazy init: true` creates the
  converter with the first request that needs it instead.
- **The shutdown**: on `SIGTERM` or Ctrl+C, `start` answers new requests with `503` and gives those in progress the
  `Timeout` of `server.stxt` to end; without any, it stops at once (`PressServer.close(Duration)`).
- **The configuration**, `server.stxt`, in `dev.stxt.press.config`: found in `--config`, then `STXT_PRESS_CONFIG`,
  then the working directory, and validated with the schema `dev.stxt.press` of the jar. It has the address, the
  name of the environment variable with the token (`Token env`), `Warm up` and `Lazy init`, the resources folder or
  separate folders of fonts and images, the templates and documents folders, the routes on (`Editor`, `Render`,
  `Files`, `Save`), the limits of the server and of the engine, the options of the templates and the documents, and
  the `Producer` of the PDFs. Relative paths are relative to the file. An error stops the start with its code, file
  and line (`ConfigException`). Without a file, the server listens only on `127.0.0.1:8080`, without token.
- **The index**, at `GET /`: the documents and the templates as trees of their folders, the SVG pages of an entry with
  the fonts of the server, its PDF, and the routes of the API; with `Save`, **New document** and **New template**.
- **The web editor**, at `/editor`, `/editor/documents/{path}` and `/editor/templates/{path}`: one tab for each file
  (the data, the document and each styles document of its chain, from the nearest), Fill, Preview (SVG pages, with or
  without the outlines of the boxes) and PDF, the errors of the server marked on their line, Copy curl and, with
  `Save`, **Save** and Ctrl+S. The pages take the look of the playground, with a dark theme, and ask for the token on a
  `401`. Built from `web/` by Maven; `Editor: false` turns them off.
- **The command line**, `java -jar stxt-press.jar <command>`: `start`, `render` (a document with its styles and data,
  to PDF), `print` (a template), `documents` (a document of the documents folder) and `fill` (the filled document).
  The errors of a job go to stderr as `file:line: CODE: message`. Exit codes: `0`, `1` if the job failed (a file that
  cannot be read or is not UTF-8 too) and `2` if the invocation is not valid. A file of `-o` is replaced only when the
  job ends well.
- **The server folder**, `server/`: a whole server that can be copied anywhere, with `stxt-press.jar`, `start.sh` and
  `start.bat`, its `server.stxt`, the fonts IBM Plex Sans, Serif and Mono, and the schemas `dev.stxt.pdf` and
  `dev.stxt.press` in `.stxt/` for the editors with discovery. In `templates/examples/` and `documents/examples/`,
  nineteen complete examples as templates and as documents (the eleven of `stxt-pdf` and eight more), and in
  `documents/reference/`, a reference of the layout language, one small document for each use.
- **The jar**: a single file with every dependency inside (`maven-shade-plugin`), with `dev.stxt.press.cli.Main` as
  its `Main-Class`. `-Dnpm.install=ci` builds the page with exactly the dependencies of `web/package-lock.json`.
