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

### Changed

- `stxt-pdf` 0.13.0-SNAPSHOT, the working tree of `../stxt-pdf` installed locally, until 0.13.0 is published. With it,
  the `dependencyManagement` that pinned PDFBox 3.0.8 is gone: `stxt-pdf` 0.13.0 excludes the PDFBox that
  `graphics2d` brings, and the enforcer's `dependencyConvergence` passes on its own.
