# Change Log

All notable changes to `stxt-press` are documented in this file, newest first.

The format follows [Keep a Changelog](http://keepachangelog.com/).

## [Unreleased]

### Added

- The Maven project, on `dev.stxt:stxt-core` 1.0.3, `dev.stxt:stxt-pdf` 0.17.0 and `dev.stxt:stxt-transform` 0.1.0,
  with Java 17.
- `EngineJobsTest`: the jobs of the service (convert with a separate styles document, fill the Slots and convert,
  preview as one SVG per page) and their errors (in the styles, in the document, in the data, a limit, a cancellation),
  done with the API of the engine as it is: a shared `Converter`, and one per request from its `toBuilder()`.
- The core of the service, without HTTP, in `dev.stxt.press`: `Press`, on the shared `Converter` of the server, with
  three jobs: `pdf` (to an `OutputStream`, written only once the document is laid out), `svg` (one SVG per page) and
  `fill` (the filled document as STXT text). A `Job` carries the layout document, its separate styles documents in
  order (each with the name it has in the errors), the JSON data and the `Options` (`pdfUa`, `pdfA`, `debug`, the
  excluded `Check`s); with data, the Slots are filled before converting. Every error is a `PressException` with
  `kind`, `code`, `line` and `source`, the input the line belongs to: `document`, `data`, `filled` (with the filled
  text, whose lines are not those of the document with Slots) or the name of a styles document.
- The templates of the server, without HTTP, in `dev.stxt.press.templates`: `Templates` reads a folder where a template
  is a `<name>.stxt`, named by its path without extension (`invoices/2026/invoice`), with its own `<name>.styles.stxt`
  and its sample data `<name>.json` beside it, all optional, and a `.styles.stxt` in any folder above it. The styles are
  chained from the root to the template, so the nearest one wins. A `Template` gives the job of a `GET` (filled with its
  sample data, or as it is without them) and that of a `POST` (filled with the data of the request instead). The
  segments of a path are `[A-Za-z0-9_-]+`; any other path is `TEMPLATE_NOT_FOUND`, and a file that is a symbolic link
  leaving the folder is not read (`TEMPLATE_LINK_OUTSIDE`). The folder is read on every call.
- The HTTP service, in `dev.stxt.press.http`, on the `HttpServer` of the JDK: `PressServer` with a `ServerConfig`
  (address, token, routes on, limits, options of the templates and the documents). Routes:
  `GET`/`POST /print/templates/{path}` (a template, filled with its sample data or with the body),
  `GET /print/documents/{path}` (a document of the documents folder, as it is), `POST /render` (any document, with its
  styles, its data as a JSON value and its options), `GET /templates`, `GET /templates/{path}`, `GET /documents`,
  `GET /documents/{path}`, `GET /schema`, `GET /fonts` and `GET /health`.
  `?format=pdf|svg|stxt` and `&debug=true` on `/print` and `/render`. Errors as JSON with `code` and `message`, and
  `kind`, `line`, `source` and `filled` for those of a conversion: `400`, `401`, `404`, `405`, `413`, `422`, `503`
  (a conversion out of time, `CANCELLED`, or the server busy, `SERVER_BUSY`) and `500`. With a token, every route but
  `/health` asks for `Authorization: Bearer`. `/render`, `/templates` and `/documents` can be turned off.
- `jackson-databind` 2.22.4, for the JSON of the HTTP service.
- The configuration of a server, `server.stxt`, in `dev.stxt.press.config`: `ServerFile` finds it (`--config`, then
  `STXT_PRESS_CONFIG`, then `server.stxt` in the working directory), validates it with the schema `dev.stxt.press` of
  the jar and starts the server with it. It sets the address, the token (only the name of its environment variable,
  `Token env`), `Lazy init`, `Warm up`, the resources folder or separate folders of fonts and images, the
  templates folder, the documents folder, the routes on, the limits of the server and of the engine, and the options
  of the templates and the documents.
  Relative paths are relative to the folder of the file. An error is a `ConfigException` with its code and its line.
  Without a file, the server listens only on `127.0.0.1:8080`, without token.
- `PressServer.startLazily`, whose shared converter is created by the first request that needs it.
- The web editor, without token, opened from the index, with what it shows in its URL, so that a reload shows it
  again: `GET /editor` (a blank document), `GET /editor/documents/{path}` (a document of the documents folder) and
  `GET /editor/templates/{path}` (a template); with changes in the panels, leaving the page asks first. A document (its
  styles, without data: a blank one, rendered with `/render`, or one of the documents folder, printed with
  `/print/documents` or, edited, rendered with `/render`) or a template (its sample data first and then its document
  and its chain of styles, printed with `/print/templates` or, edited, rendered with `/render`, and its `curl`). The
  page fills the window, without a scroll of its own: the bar has the path of what it shows, which links back to its
  folder in the index, and the buttons; on the left, one tab for each file (the data, the document and each styles
  document), marked when it has an error or a warning; on the right, the output of the last button. Fill (for a
  template), preview as SVG pages with or without outlines, and the PDF, shown in the viewer of the browser with a link
  that downloads it; the errors of the server are marked in their panel, on their line, and their tab is shown. If
  `/fonts` fails, the next preview asks for the
  fonts again. With a token, the page asks for it on a `401`. It is turned off with `Editor: false` in the
  `Routes` of `server.stxt`. Built from `web/` by Maven, with Node installed by `frontend-maven-plugin`.
- The index, at `GET /`, without token, filling the window in two halves: on the left a tab for the documents (the
  one it opens with), one for the templates and one for the routes of the API, and a link to a new document; the
  documents and the templates tabs show the tree of their folders, each with how many entries it has, and the entries
  of the folder chosen and below it, each with a link to the editor and a PDF button. On the right, the PDF of the last
  PDF button, in the viewer of the browser, with links to edit and to download it. What it shows is in its hash (`/#documents/reference/01-values`), and the
  name of the editor links back to the folder of what it shows. The files of the index and the
  editor are served under `/assets/`. It is turned off with the editor.
- `GET /fonts` also lists the faces of the fonts (family, weight, style and the SHA-256 of the file), and
  `GET /fonts/{id}` gives the file of a face: the SVG pages name their fonts and do not carry them. Both are for the
  previews of the editor and are turned off with it (`Editor: false`): no other client needs the files of the fonts.
- `server/`, a server folder to start a project from: its `server.stxt`, the fonts IBM Plex Sans, Serif and Mono and
  the images of the examples of `stxt-pdf` in `resources/`, and sample templates in `templates/`: one for each
  example of `stxt-pdf` (an invoice, a letter, a resume, an annual report, a newsletter, a menu, a contract, a manual,
  a catalog, a certificate and a timetable), with Slots for what changes from one document to another, its styles and
  its sample data, which give the pages of the example; and documents in `documents/`: complete examples of other
  kinds of documents (`examples/`) and a reference of the layout language, one small document for each use, with a
  line that says what it shows (`reference/`). Its `.stxt/` has the schemas `dev.stxt.pdf` and `dev.stxt.press`, for the
  editors with discovery. `start --config server/server.stxt` starts it.
- `start.sh`, and `start.bat` for Windows, in `server/`: they start the server of the folder with the
  `stxt-press.jar` beside them, which `mvn package` copies there, so the folder is a whole server that can be copied
  anywhere. The Java is that of `JAVA_HOME`, or the `java` of the `PATH`.
- The command line, `java -jar stxt-press.jar <command>`: `start` (the HTTP service with its `server.stxt`, which
  stops on `SIGTERM` letting the conversions in progress finish), `render` (a document with its styles and data, to
  PDF), `print` (a template, to PDF) and `fill` (the filled document). The errors of a job go to stderr with their file
  and line. Exit codes: `0`, `1` if the job failed, `2` if the invocation is not valid.
- The jar is a single file with every dependency inside (`maven-shade-plugin`), with `dev.stxt.press.cli.Main` as
  its `Main-Class`.
- The warm up of the server (`Warm up` in `server.stxt`, on by default): before answering, it reads the whole resources
  folder and converts every template with its sample data and every document, to PDF and to SVG, so that the first request is as fast as
  the next ones (the catalog of `server/`, 1.3 s cold and 0.3 s warm). It listens from the start, and until it ends
  every route, `/health` too, answers `503` with the code `SERVER_STARTING` and `Retry-After: 1`. A template or a
  document that cannot be converted is a warning in the log, or only information if it has no sample data. It replaces `Preload
  resources`; `Lazy init: true` turns it off, and both `true` is `CONFIG_CONFLICT`. `PressServer.startWarmingUp` and
  `PressServer.warmUp()` for it.
- `PressServer.close(Duration)`, which answers new requests with `503` and waits for those in progress, and without
  any stops at once: it does not use `HttpServer.stop(seconds)`, which on Java 17 waits all the seconds even without
  requests, so a Ctrl+C of `start` took the whole `Timeout`.
- `GET /health` gives the versions of the service and of the engine (`Converter.version()` of `stxt-pdf`), the first
  `null` outside the jar.
- The PDFs of the server and of `render` and `print` name the service and the engine as their producer
  (`stxt-press 0.1.0 (stxt-pdf 0.17.0)`, `Press.producer()`), in the document information and in the XMP, with
  `Converter.Builder.producer` of `stxt-pdf` 0.14.0, or the `Producer` of `server.stxt` (`ServerFile.producer()`). A
  `Press` built on a `Converter` of its own writes what that one writes.
- Time limits on the requests of `start`: a request, its headers and its body, has to arrive whole in 30 s, and its
  response has to be sent whole in the `Timeout` of `server.stxt` and 30 s more, or the connection is closed. They are
  `sun.net.httpserver.maxReqTime` and `sun.net.httpserver.maxRspTime` of the JDK, set by `PressServer.limitTimes`
  unless given with `-D`. Without them, a client that sent the headers of a request and not its body kept a thread,
  and eight such clients left a server with the default limits without answering, `/health` too. A body that does not
  arrive whole is a `400` (`BODY_NOT_VALID`).
- `X-Content-Type-Options: nosniff` on every response, not only on the files of `/assets/`.
- A server without token on a loopback address answers only to the `Host` `localhost`, `127.0.0.1`, `[::1]` or that
  of `server.stxt`; any other, or none, is `403` with the code `HOST_NOT_ALLOWED`, on every route, `/health` too. Before,
  a page of another site could make its own name resolve to `127.0.0.1` (DNS rebinding) and read the templates or use
  `/render` from the browser of whoever runs the server.
- `render` and `print` read only the fonts and images of their document, not the whole resources folder: the warm up
  is of the server. The invoice of `server/` prints in 1.9–2.1 s instead of 2.3 s, with the same PDF. `Press.fill` is
  static, so `fill` creates no converter, and a fill (`?format=stxt`) on a server with `Lazy init` does not create
  its converter either: with resources that cannot be read, it is answered.
- `Options.named` and `Options.names`, the lookup of a `Check` or a `PdfA` by its name and the list of the names for a
  message, which the body of `/render` and the command `render` share; `Press.text`, the strict UTF-8 decoding of the
  body of `POST /print` and of the stdin of the commands; and `Templates.files`, the file of each input of a template,
  for the errors of `print`. The `CONFIG_CONFLICT` of `PDF UA: 2` with `PDF A` in `server.stxt` has the message of
  `Options`, which names the PDF/A (`A2B`).
- `-Dnpm.install=ci` runs `npm ci` instead of `npm install` for the dependencies of the page: exactly those of
  `web/package-lock.json`, checked against their hashes, failing if the lock does not fit `package.json`. The CI uses
  it, and so does the jar of a version (`mvn clean package -Dnpm.install=ci`).
- The documents folder (`Document folder` in `server.stxt`), beside the templates folder: layout documents printed as
  they are, with the rules of the templates folder (a path of `[A-Za-z0-9_-]+` segments, the chain of `.styles.stxt`
  from the root, links that leave the folder not read) and without data, whose errors are `DOCUMENT_NOT_FOUND` and
  `DOCUMENT_LINK_OUTSIDE`. `Templates` reads either kind of folder (`Templates.Kind`). `/print/{path}` became
  `/print/templates/{path}`, beside `/print/documents/{path}`; `GET /documents` and `GET /documents/{path}` list them
  and give their texts, and `Documents: false` in `Routes` turns them off. In the editor, `/editor/{path}` became
  `/editor/templates/{path}`, and `/editor/{path}?mode=document` (a template filled with its sample data, as a
  document) gave way to `/editor/documents/{path}`.
- The save of the templates and the documents (`Save: true` in the `Routes` of `server.stxt`, off by default):
  `PUT /templates/{path}` and `PUT /documents/{path}` write the files of the body, which has the shape of the `GET`,
  if `If-Match` has the version they are at (the `ETag` of the `GET`, the SHA-256 of their names and texts,
  `Template.version()`); from another version nothing is written (`409`, `FILES_CHANGED`). Only the files of the chain
  are written, those whose text changes, whole and with the line ends they had; the own styles and the sample data are
  created if their text is not blank, and nothing is deleted (`Templates.save`). A save needs
  `Content-Type: application/json` (`415`, `CONTENT_TYPE_NOT_VALID`) and `If-Match` (`428`, `VERSION_REQUIRED`). The
  `GET` of a template or a document gives its `ETag` and whether it is saved (`writable`).
- In the editor, **Save** and Ctrl+S, with `Save` on: the files that changed, asking first for a `.styles.stxt` of a
  folder. Each half of the editor, and the PDF of the index, has a button that puts it over the whole window, and Esc
  puts it back (the idea of the editor of `pdf_gen`). The buttons say how long they took (`PDF in 312 ms.`).
- The `server.stxt` of `server/` listens on `127.0.0.1:8082`, and has `Save: true`.
