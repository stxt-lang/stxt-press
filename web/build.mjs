// Builds the editor into the folder of its argument (the Maven build gives target/classes/editor, so that the files go
// into the jar under /editor/): editor.js with everything bundled, editor.css, and index.html.
import { build } from "esbuild";
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(process.argv[2] ?? "dist");

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

await build({
    entryPoints: [join(here, "src/main.ts")],
    bundle: true,
    format: "iife",
    target: "es2021",
    minify: true,
    legalComments: "none",
    outfile: join(out, "editor.js"),
    logLevel: "warning",
});

const library = readFileSync(join(here, "node_modules/@stxt-lang/editor/dist/stxt-editor.css"), "utf8");
writeFileSync(join(out, "editor.css"), library + "\n" + readFileSync(join(here, "src/editor.css"), "utf8"));
copyFileSync(join(here, "src/index.html"), join(out, "index.html"));
