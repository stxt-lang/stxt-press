# stxt-press

PDF and SVG generation service for [STXT](https://stxt.dev) layout documents (`dev.stxt.pdf`). The layout, the PDF
and the SVG pages are the work of [stxt-pdf](https://github.com/stxt-lang/stxt-pdf), and the fill of Slots with JSON data,
of [stxt-transform](https://github.com/stxt-lang/stxt-java-transform): this project does not parse, validate or lay out
anything itself.

In development: no version has been released.

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

## License

[MIT](LICENSE), copyright `stxt-lang`.
