// Builds the editor into the folder of its argument (the Maven build gives target/classes/editor, so that the files go
// into the jar under /editor/): editor.js with everything bundled, editor.css, and index.html. The examples of the
// document mode are those of stxt-pdf (../../stxt-pdf/test/examples), read here and bundled, not copied by hand.
import { build } from "esbuild";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(process.argv[2] ?? "dist");
const examples = resolve(here, "../../stxt-pdf/test/examples");

if (!existsSync(examples)) {
    console.error(`No examples of stxt-pdf in ${examples}: the editor is built with ../stxt-pdf next to stxt-press.`);
    process.exit(1);
}

/** Every example of stxt-pdf, in every language: its document and the styles of the example, if it has them. */
function readExamples() {
    const list = [];
    for (const example of readdirSync(examples).sort()) {
        const folder = join(examples, example);
        if (!statSync(folder).isDirectory()) {
            continue;
        }
        const stylesFile = join(folder, "styles.stxt");
        const styles = existsSync(stylesFile) ? readFileSync(stylesFile, "utf8") : "";
        for (const language of readdirSync(folder).sort()) {
            const input = join(folder, language, "input.stxt");
            if (existsSync(input)) {
                list.push({ name: `${example} (${language})`, document: readFileSync(input, "utf8"), styles });
            }
        }
    }
    if (list.length === 0) {
        console.error(`No examples in ${examples}`);
        process.exit(1);
    }
    return list;
}

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
    define: { EXAMPLES: JSON.stringify(readExamples()) },
    logLevel: "warning",
});

const library = readFileSync(join(here, "node_modules/@stxt-lang/editor/dist/stxt-editor.css"), "utf8");
writeFileSync(join(out, "editor.css"), library + "\n" + readFileSync(join(here, "src/editor.css"), "utf8"));
copyFileSync(join(here, "src/index.html"), join(out, "index.html"));
