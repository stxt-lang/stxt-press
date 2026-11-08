# Change Log

All notable changes to `stxt-press` are documented in this file, newest first.

The format follows [Keep a Changelog](http://keepachangelog.com/).

## [Unreleased]

### Added

- The Maven project, on `dev.stxt:stxt-core` 1.0.3, `dev.stxt:stxt-pdf` 0.12.0 and `dev.stxt:stxt-transform` 0.1.0,
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
  (address, token, routes on, limits, options of the templates). Routes: `GET`/`POST /print/{path}` (a template,
  filled with its sample data or with the body), `POST /render` (any document, with its styles, its data as a JSON
  value and its options), `GET /templates`, `GET /templates/{path}`, `GET /schema`, `GET /fonts` and `GET /health`.
  `?format=pdf|svg|stxt` and `&debug=true` on `/print` and `/render`. Errors as JSON with `code` and `message`, and
  `kind`, `line`, `source` and `filled` for those of a conversion: `400`, `401`, `404`, `405`, `413`, `422`, `503`
  (a conversion out of time, `CANCELLED`, or the server busy, `SERVER_BUSY`) and `500`. With a token, every route but
  `/health` asks for `Authorization: Bearer`. `/render` and `/templates` can be turned off.
- `jackson-databind` 2.22.4, for the JSON of the HTTP service.

### Changed

- `stxt-pdf` 0.13.0-SNAPSHOT, the working tree of `../stxt-pdf` installed locally, until 0.13.0 is published. With it,
  the `dependencyManagement` that pinned PDFBox 3.0.8 is gone: `stxt-pdf` 0.13.0 excludes the PDFBox that
  `graphics2d` brings, and the enforcer's `dependencyConvergence` passes on its own.
