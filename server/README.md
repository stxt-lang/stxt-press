# A server folder

Everything a server of stxt-press reads, in one folder that can be copied as the start of a project:

```bash
java -jar stxt-press.jar serve --config server/server.stxt
```

| Path | What it has |
|---|---|
| `server.stxt` | The configuration: address, port, folders, routes, limits. Relative paths are relative to this folder |
| `resources/fonts/` | IBM Plex Sans, Serif and Mono, each with its license (`OFL.txt`) |
| `resources/images/examples/` | The images of the examples of stxt-pdf, the ones the editor opens |
| `templates/` | The templates of `/print`: `invoices/invoice` with its styles and its sample data, and `.styles.stxt` with the styles of every template of the folder |

The fonts and the images are copies of those of `../stxt-pdf/resources`, and a test checks that they are the same.
