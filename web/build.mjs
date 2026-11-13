// Builds the editor into the folder of its argument (the Maven build gives target/classes/editor, so that the files go
// into the jar under /editor/, which the server gives at /assets/): the index (index.html and index.js) and the editor
// (editor.html and editor.js, with CodeMirror), theme.js for the head of both, each script with everything bundled,
// editor.css for both (with the light and the dark theme), and the font of the wordmark and the icons.
import { build } from "esbuild";
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = resolve(process.argv[2] ?? "dist");

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

await build({
    entryPoints: { editor: join(here, "src/main.ts"), index: join(here, "src/index.ts"), theme: join(here, "src/themeHead.ts") },
    bundle: true,
    format: "iife",
    target: "es2021",
    minify: true,
    legalComments: "none",
    outdir: out,
    logLevel: "warning",
});

const library = readFileSync(join(here, "node_modules/@stxt-lang/editor/dist/stxt-editor.css"), "utf8");
const dark = readFileSync(join(here, "node_modules/@stxt-lang/editor/dist/stxt-editor-dark.css"), "utf8");
const pages = readFileSync(join(here, "src/editor.css"), "utf8");
writeFileSync(join(out, "editor.css"), [library, darkOnly(library, dark), pages].join("\n"));
// The pages, and the files they load as they are: the font of the wordmark (Russo One, with its licence) and the icons.
for (const file of ["index.html", "editor.html", "russo-one-latin.woff2", "russo-one-OFL.txt", "favicon-16.png", "favicon-32.png"]) {
    copyFileSync(join(here, "src", file), join(out, file));
}

/**
 * The dark theme of @stxt-lang/editor under the `data-theme` of the page, as the playground has it: the rules that its
 * dark stylesheet adds after the light one (only custom properties and the colour scheme), each selector after
 * `:root[data-theme="dark"]`, so that they win over the light ones when the page is dark.
 */
function darkOnly(light, dark) {
    if (!dark.startsWith(light)) {
        throw new Error("stxt-editor-dark.css is no longer stxt-editor.css with the dark rules after it");
    }
    const rules = dark.slice(light.length).replace(/\/\*[\s\S]*?\*\//g, "");
    if (rules.includes("@")) {
        throw new Error("The dark rules of stxt-editor-dark.css have an at-rule, which this build does not scope");
    }
    return rules.replace(/([^{}]+)\{([^{}]*)\}/g, (_, selectors, body) => selectors.split(",")
        .map((selector) => `:root[data-theme="dark"] ${selector.trim()}`).join(",\n") + " {" + body + "}\n");
}
