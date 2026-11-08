# A server folder

Everything a server of stxt-press needs, in one folder that can be copied anywhere and started from there with its
script. It needs Java 17 or later: that of `JAVA_HOME` if it is set, or the `java` of the `PATH`.

```bash
./start.sh
```

On Windows, `start.bat`. The server listens on `http://127.0.0.1:8080/`, where the editor is, and Ctrl+C stops it.

| Path | What it has |
|---|---|
| `start.sh`, `start.bat` | The scripts that start the server: `java -jar stxt-press.jar start --config server.stxt`, in this folder |
| `stxt-press.jar` | The server, with every dependency inside. In the project of stxt-press, `mvn package` puts it here |
| `server.stxt` | The configuration: address, port, folders, routes, limits. Relative paths are relative to this folder |
| `resources/fonts/` | IBM Plex Sans, Serif and Mono, each with its license (`OFL.txt`) |
| `resources/images/examples/` | The images of the examples of stxt-pdf, the ones the editor opens |
| `templates/` | The templates of `/print`: `invoices/invoice` with its styles and its sample data, and `.styles.stxt` with the styles of every template of the folder |

The fonts and the images are copies of those of `../stxt-pdf/resources`, and a test checks that they are the same.
