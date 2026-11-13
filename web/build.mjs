// Builds the editor into the folder of its argument (the Maven build gives target/classes/editor, so that the files go
// into the jar under /editor/, which the server gives at /assets/): the index (index.html and index.js) and the editor
// (editor.html and editor.js, with CodeMirror), each script with everything bundled, editor.css for both, and the font
// of the wordmark and the icons.
import { build } from "esbuild";
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(process.argv[2] ?? "dist");

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

await build({
    entryPoints: { editor: join(here, "src/main.ts"), index: join(here, "src/index.ts") },
    bundle: true,
    format: "iife",
    target: "es2021",
    minify: true,
    legalComments: "none",
    outdir: out,
    logLevel: "warning",
});

const library = readFileSync(join(here, "node_modules/@stxt-lang/editor/dist/stxt-editor.css"), "utf8");
writeFileSync(join(out, "editor.css"), library + "\n" + readFileSync(join(here, "src/editor.css"), "utf8"));
// The pages, and the files they load as they are: the font of the wordmark (Russo One, with its licence) and the icons.
for (const file of ["index.html", "editor.html", "russo-one-latin.woff2", "russo-one-OFL.txt", "favicon-16.png", "favicon-32.png"]) {
    copyFileSync(join(here, "src", file), join(out, file));
}
