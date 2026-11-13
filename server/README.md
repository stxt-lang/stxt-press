# A server folder

Everything a server of stxt-press needs, in one folder that can be copied anywhere and started from there with its
script. It needs Java 17 or later: that of `JAVA_HOME` if it is set, or the `java` of the `PATH`.

```bash
./start.sh
```

On Windows, `start.bat`. The server listens on `http://127.0.0.1:8082/`, where the editor is, and Ctrl+C stops it.

| Path | What it has |
|---|---|
| `start.sh`, `start.bat` | The scripts that start the server: `java -jar stxt-press.jar start --config server.stxt`, in this folder |
| `stxt-press.jar` | The server, with every dependency inside. In the project of stxt-press, `mvn package` puts it here |
| `server.stxt` | The configuration: address, port, folders, routes, limits. Relative paths are relative to this folder |
| `resources/fonts/` | IBM Plex Sans, Serif and Mono, each with its license (`OFL.txt`) |
| `resources/images/examples/` | The images of the examples of stxt-pdf, the ones the templates use |
| `resources/images/documents/` | The images of the documents of `documents/`, small hand-written SVGs |
| `.stxt/` | The schemas `dev.stxt.pdf` (layout documents and styles) and `dev.stxt.press` (`server.stxt`), found by the editors that support [discovery](https://stxt.dev/stxt-discovery-ref) for every file of the folder: VS Code with the STXT extension, the `stxt` command |
| `templates/` | The templates of `/print/templates`, one for each example of stxt-pdf, in a folder for each kind of document (`invoices/invoice`, `letters/letter`, `reports/annual-report`…), each one with its styles and its sample data |
| `documents/examples/` | Documents of `/print/documents` of other kinds than the templates, complete, each with its styles |
| `documents/reference/` | A reference of the layout language: one small document for each use, by topic (`14-tables/`, `20-barcodes/`…), each starting with what it shows |

The fonts and the images of `resources/images/examples/` are copies of those of `../stxt-pdf/resources`, and the
schemas, of those inside `stxt-press.jar`; a test checks that they are the same, that each template with its sample
data gives the pages of its example, and that every document converts. A template with Slots validates only once filled: the editor
marks the Slot of a node without value, such as `Row: [lines]`, until then.
