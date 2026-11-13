/**
 * The panels of the editor and their tabs: an editor of STXT or of JSON for each file of the place shown, one tab each
 * in `#tabs`, and the errors of the server marked on their lines. This module owns them: whoever needs one asks for it
 * (`panelOf`, `texts`). The read-only panel of the filled document is made here too, but output.ts owns it.
 */
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { lintGutter, setDiagnostics } from "@codemirror/lint";
import type { Diagnostic as CmDiagnostic } from "@codemirror/lint";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { mount, toCmDiagnostics } from "@stxt-lang/editor";
import type { Editor } from "@stxt-lang/editor";

import { $ } from "./api";
import { tabState, tabs } from "./model";
import type { Panels, Target } from "./model";

/** A panel: an editor, of STXT or of JSON, and the tab that shows it, if it is one of the files of `#panels`. */
export interface Panel {
	element: HTMLElement;
	view: EditorView;
	text(): string;
	/** The local problems of the panel, without the one of the server. */
	local(): CmDiagnostic[];
	/** Of an STXT panel: whether it validates against the schema. */
	setValidation?(on: boolean): void;
	destroy(): void;
	tab?: HTMLButtonElement;
	/** Whether it has an error of the server marked, until it changes. */
	marked?: boolean;
}

/** The schema `dev.stxt.pdf` of the server, which the STXT panels validate against; blank without it. */
let schema = "";
let documentPanel: Panel | undefined;
let stylesPanels: Panel[] = [];
/** The data panel of the templates mode; the document mode has none. */
let dataPanel: Panel | undefined;

/** Sets the schema of the STXT panels made from now on. */
export function setSchema(text: string): void {
	schema = text;
}

// ---- The editors

/** An STXT panel, with the schema of the server. */
export function stxtPanel(value: string, options: { readOnly?: boolean; onChange?: () => void } = {}): Panel {
	const element = section();
	let ready = false;
	const editor: Editor = mount(element.appendChild(document.createElement("div")), {
		value,
		grammars: schema ? [schema] : [],
		readOnly: options.readOnly ?? false,
		developerInfo: false,
		onChange: () => {
			if (ready) {
				panel.marked = false;
				refreshTab(panel);
				options.onChange?.();
			}
		},
	});
	const panel: Panel = {
		element,
		view: editor.view,
		text: () => editor.getValue(),
		local: () => toCmDiagnostics(editor.view.state.doc, editor.getDiagnostics()),
		setValidation: (on) => {
			editor.setValidation(on);
			refreshTab(panel);
		},
		destroy: () => {
			editor.destroy();
			element.remove();
		},
	};
	ready = true;
	return panel;
}

function jsonPanel(value: string, onChange: () => void): Panel {
	const element = section();
	// With the class of the STXT panels, the look of @stxt-lang/editor: the same gutter, fonts and colours, dark too.
	const holder = element.appendChild(document.createElement("div"));
	holder.className = "stxt-editor";
	const view = new EditorView({
		parent: holder,
		state: EditorState.create({
			doc: value,
			extensions: [
				lineNumbers(),
				history(),
				lintGutter(),
				keymap.of([...defaultKeymap, ...historyKeymap]),
				EditorView.updateListener.of((update) => {
					if (update.docChanged) {
						// An error of the server is of the text it was sent.
						update.view.dispatch(setDiagnostics(update.view.state, []));
						panel.marked = false;
						refreshTab(panel);
						onChange();
					}
				}),
			],
		}),
	});
	const panel: Panel = {
		element,
		view,
		text: () => view.state.doc.toString(),
		local: () => [],
		destroy: () => {
			view.destroy();
			element.remove();
		},
	};
	return panel;
}

function section(): HTMLElement {
	const element = document.createElement("div");
	element.className = "panel";
	return element;
}

// ---- The panels of the place shown

/**
 * Shows `panels`, one tab each, in the order of `tabs`, and the first one. `onEdit` is called when any of them is
 * edited, and `onData` when the data panel is, after it.
 */
export function showPanels(panels: Panels, onEdit: () => void, onData: () => void): void {
	clearPanels();
	documentPanel = stxtPanel(panels.document.text, { onChange: onEdit });
	stylesPanels = panels.styles.map((item) => stxtPanel(item.text, { onChange: onEdit }));
	dataPanel = panels.data === undefined ? undefined : jsonPanel(panels.data.text, () => {
		onEdit();
		onData();
	});
	const order = tabs(panels).map(({ target, title }): [Panel, string] => [panelOf(target)!, title]);
	order.forEach(([panel, title], i) => addTab(panel, title, i));
	$("panels").append(...order.map(([panel]) => panel.element));
	activate(order[0][0]);
}

/** Removes the panels and their tabs. */
export function clearPanels(): void {
	for (const panel of editable()) {
		panel.destroy();
	}
	documentPanel = undefined;
	stylesPanels = [];
	dataPanel = undefined;
	$("tabs").replaceChildren();
}

/** The panel of `where`, if the place shown has it; the filled document is not one of these (output.ts). */
export function panelOf(where: Target): Panel | undefined {
	switch (where.panel) {
		case "document":
			return documentPanel;
		case "data":
			return dataPanel;
		case "filled":
			return undefined;
		case "styles":
			return stylesPanels[where.index];
	}
}

/** The texts of the panels: the document, each styles document in order, and the data (blank without its panel). */
export function texts(): { document: string; styles: string[]; data: string } {
	return {
		document: documentPanel?.text() ?? "",
		styles: stylesPanels.map((panel) => panel.text()),
		data: dataPanel?.text() ?? "",
	};
}

/** Whether the document panel validates against the schema. */
export function validateDocument(on: boolean): void {
	documentPanel?.setValidation?.(on);
}

/** The panels of the files, which the user edits. */
function editable(): Panel[] {
	return [...(documentPanel ? [documentPanel] : []), ...stylesPanels, ...(dataPanel ? [dataPanel] : [])];
}

/** Takes the errors of the server off every panel: they are of a request before the one being made. */
export function unmarkAll(): void {
	for (const panel of editable()) {
		panel.view.dispatch(setDiagnostics(panel.view.state, panel.local()));
		panel.marked = false;
		refreshTab(panel);
	}
}

/** Marks the line of an error in its panel, until the panel changes, and shows the panel. */
export function mark(panel: Panel, line: number, message: string): void {
	if (panel.tab) {
		activate(panel);
	}
	panel.marked = true;
	refreshTab(panel);
	const diagnostics = panel.local();
	if (line > 0 && line <= panel.view.state.doc.lines) {
		const at = panel.view.state.doc.line(line);
		diagnostics.push({ from: at.from, to: at.to, severity: "error", message, source: "server" });
		panel.view.dispatch({ selection: { anchor: at.from }, scrollIntoView: true });
	}
	panel.view.dispatch(setDiagnostics(panel.view.state, diagnostics));
}

// ---- The tabs

/** The panels with a tab, in the order of the tabs. */
function tabbed(): Panel[] {
	return [...(dataPanel ? [dataPanel] : []), ...(documentPanel ? [documentPanel] : []), ...stylesPanels]
		.filter((panel) => panel.tab);
}

/** Gives `panel` a tab named `title` in `#tabs`. */
function addTab(panel: Panel, title: string, index: number): void {
	const tab = document.createElement("button");
	tab.type = "button";
	tab.id = `tab-${index}`;
	tab.textContent = title;
	tab.setAttribute("role", "tab");
	tab.setAttribute("aria-controls", `panel-${index}`);
	tab.onclick = () => activate(panel);
	tab.onkeydown = (event) => moveTab(panel, event);
	panel.element.id = `panel-${index}`;
	panel.element.setAttribute("role", "tabpanel");
	panel.element.setAttribute("aria-labelledby", tab.id);
	panel.tab = tab;
	$("tabs").appendChild(tab);
	refreshTab(panel);
}

/** Shows `panel` and hides the other panels with a tab. */
function activate(panel: Panel): void {
	for (const other of tabbed()) {
		const on = other === panel;
		other.element.hidden = !on;
		other.tab!.setAttribute("aria-selected", String(on));
		other.tab!.tabIndex = on ? 0 : -1;
	}
	// A hidden editor did not measure its lines.
	panel.view.requestMeasure();
}

/** The arrow keys, Home and End move between the tabs, as in a tab list. */
function moveTab(panel: Panel, event: KeyboardEvent): void {
	const panels = tabbed();
	const at = panels.indexOf(panel);
	const to = { ArrowLeft: at - 1, ArrowRight: at + 1, Home: 0, End: panels.length - 1 }[event.key];
	if (to === undefined) {
		return;
	}
	event.preventDefault();
	const next = panels[(to + panels.length) % panels.length];
	activate(next);
	next.tab!.focus();
}

/** Marks the tab of a panel with its problems, as `tabState` says. */
function refreshTab(panel: Panel): void {
	if (panel.tab) {
		const state = tabState(panel.marked === true, panel.local().map((diagnostic) => diagnostic.severity));
		panel.tab.classList.toggle("error", state === "error");
		panel.tab.classList.toggle("warning", state === "warning");
		panel.tab.title = state === "error" ? "Has errors" : state === "warning" ? "Has warnings" : "";
	}
}
