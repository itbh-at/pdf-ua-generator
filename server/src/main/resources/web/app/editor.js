/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

// The code editor of the UI. It works only through the JSON API: it reads the files of the base
// revision, checks the edited files while typing (problems come from the server, the browser does
// not parse Qute), previews them without saving, and saves them as a new revision. Language
// variants can be added, removed, and edited beside one another.
import { EditorView, basicSetup } from 'codemirror';
import { EditorState } from '@codemirror/state';
import { html } from '@codemirror/lang-html';
import { json } from '@codemirror/lang-json';
import { css } from '@codemirror/lang-css';
import { lintGutter, setDiagnostics } from '@codemirror/lint';

const DEFAULT_VARIANT = 'template.xhtml';
const VARIANT = /^template\.([A-Za-z0-9-]+)\.xhtml$/;

const root = document.getElementById('editor');
if (root) {
  start(root);
}

function start(root) {
  const id = root.dataset.id;
  const base = Number(root.dataset.base);
  const defaultLang = root.dataset.defaultLang;
  const api = `/templates/${encodeURIComponent(id)}/revisions/${base}`;
  const original = {}; // path -> text of the base revision ('' for a file the base lacks)
  const edited = {}; // path -> text, only files that differ from the base
  const deleted = new Set(); // paths of the base removed in the edit
  let problems = [];
  let timer = null;
  let previewing = null; // the running preview request, aborted when a newer one starts

  const $ = (selector) => root.querySelector(selector);
  const nav = $('.files');
  const state = $('.state');
  const messages = $('.messages');
  const save = $('.save');
  const live = $('input[name="live"]');
  const previewState = $('.preview-state');
  const langSelect = $('select[name="lang"]');
  const compareSelect = $('select[name="compare"]');
  const removeLang = $('.remove-lang');

  // Two editors: the main one on the left, and one on the right to compare language variants.
  const main = editor($('.source .code'));
  const other = editor($('.compare-pane .code'));

  nav.addEventListener('click', (e) => {
    const tab = e.target.closest('[data-file]');
    if (tab) open(main, tab.dataset.file);
  });
  save.addEventListener('click', saveRevision);
  $('.preview').addEventListener('click', preview);
  root.querySelectorAll('.preview-pane select').forEach((s) => s.addEventListener('change', preview));
  live.addEventListener('change', () => live.checked && preview());
  $('.add-lang').addEventListener('click', addLanguage);
  removeLang.addEventListener('click', removeLanguage);
  root.querySelectorAll('input[name="mode"]').forEach((r) => r.addEventListener('change', mode));
  compareSelect.addEventListener('change', () => open(other, compareSelect.value));
  window.addEventListener('beforeunload', (e) => {
    if (changes()) e.preventDefault();
  });

  const first = tabs()[0];
  if (first) open(main, first.dataset.file);
  preview();

  function editor(parent) {
    return { view: new EditorView({ parent }), states: {}, current: null };
  }

  function tabs() {
    return [...nav.querySelectorAll('[data-file]')].filter((t) => !t.hidden);
  }

  function variants() {
    return tabs()
      .map((t) => t.dataset.file)
      .filter((f) => f === DEFAULT_VARIANT || VARIANT.test(f));
  }

  function langOf(file) {
    if (file === DEFAULT_VARIANT) return defaultLang;
    const match = VARIANT.exec(file);
    return match ? match[1] : null;
  }

  function text(file) {
    return edited[file] ?? original[file] ?? '';
  }

  async function load(file) {
    if (!(file in original)) {
      const path = file.split('/').map(encodeURIComponent).join('/');
      const response = await fetch(`${api}/files/${path}`);
      original[file] = response.ok ? await response.text() : '';
    }
  }

  async function open(ed, file, line) {
    if (!file) return;
    if (ed.current) ed.states[ed.current] = ed.view.state;
    // The same file cannot be open on both sides: the other side moves to another variant.
    const opposite = ed === main ? other : main;
    if (opposite.current === file && comparing()) {
      const alternative = variants().find((f) => f !== file);
      if (ed === main) {
        await open(other, alternative);
      } else {
        compareSelect.value = main.current;
        return;
      }
    }
    await load(file);
    ed.current = file;
    ed.view.setState(
      ed.states[file] ||
        EditorState.create({
          doc: text(file),
          extensions: [
            basicSetup,
            language(file),
            lintGutter(),
            EditorView.lineWrapping,
            EditorView.updateListener.of((update) => {
              if (update.docChanged) changed(file, update.state.doc.toString(), ed);
            }),
          ],
        }),
    );
    if (ed === main) {
      tabs().forEach((t) => t.setAttribute('aria-selected', String(t.dataset.file === file)));
      removeLang.hidden = !VARIANT.test(file);
      // The preview shows the language of the variant being edited.
      const lang = langOf(file);
      if (lang && langSelect.value !== lang && [...langSelect.options].some((o) => o.value === lang)) {
        langSelect.value = lang;
        if (live.checked && !comparing()) preview();
      }
      fillCompare();
    }
    showDiagnostics(ed);
    if (line) {
      const target = ed.view.state.doc.line(Math.min(line, ed.view.state.doc.lines));
      ed.view.dispatch({ selection: { anchor: target.from }, scrollIntoView: true });
      ed.view.focus();
    }
  }

  function language(file) {
    if (file.endsWith('.json')) return json();
    if (file.endsWith('.css')) return css();
    if (file.endsWith('.xhtml')) return html();
    return [];
  }

  function changed(file, value, ed) {
    // A new variant is always saved, even if emptied; a base file only if it differs.
    if (value === original[file] && !isNew(file)) {
      delete edited[file];
    } else {
      edited[file] = value;
    }
    // Keep the other editor in step if it shows an older state of the same file.
    const opposite = ed === main ? other : main;
    delete opposite.states[file];
    refresh();
  }

  function isNew(file) {
    return nav.querySelector(`[data-file="${CSS.escape(file)}"]`)?.dataset.new === 'true';
  }

  function changes() {
    return Object.keys(edited).length + deleted.size;
  }

  function refresh() {
    tabs().forEach((t) => t.classList.toggle('dirty', t.dataset.file in edited));
    const count = changes();
    save.disabled = count === 0;
    state.textContent = count ? `${count} change(s), not saved.` : 'No changes.';
    clearTimeout(timer);
    timer = setTimeout(check, 600);
  }

  function addLanguage() {
    const input = $('input[name="new-lang"]');
    let tag;
    try {
      tag = Intl.getCanonicalLocales(input.value.trim())[0];
    } catch {
      tag = null;
    }
    if (!tag) {
      show([{ detail: `'${input.value}' is not a language tag such as fr or de-AT.` }], []);
      return;
    }
    const file = `template.${tag}.xhtml`;
    if (tag === defaultLang || variants().includes(file)) {
      show([{ detail: `The document template is already written in ${tag}.` }], []);
      return;
    }
    // The new variant starts as a copy of the default variant, to be translated.
    Promise.resolve(load(DEFAULT_VARIANT)).then(() => {
      deleted.delete(file);
      const hidden = nav.querySelector(`[data-file="${CSS.escape(file)}"]`);
      const tab = hidden || document.createElement('button');
      if (!hidden) {
        tab.type = 'button';
        tab.setAttribute('role', 'tab');
        tab.dataset.file = file;
        tab.dataset.new = 'true';
        tab.textContent = file;
        const after = [...nav.children].filter((t) => t.dataset.file === DEFAULT_VARIANT || VARIANT.test(t.dataset.file)).pop();
        after ? after.after(tab) : nav.prepend(tab);
        original[file] = '';
      }
      tab.hidden = false;
      edited[file] = text(DEFAULT_VARIANT);
      if (![...langSelect.options].some((o) => o.value === tag)) {
        langSelect.add(new Option(tag, tag));
      }
      input.value = '';
      refresh();
      open(main, file);
    });
  }

  function removeLanguage() {
    const file = main.current;
    if (!VARIANT.test(file)) return;
    const tab = nav.querySelector(`[data-file="${CSS.escape(file)}"]`);
    tab.hidden = true;
    delete edited[file];
    delete main.states[file];
    delete other.states[file];
    if (tab.dataset.new !== 'true') deleted.add(file);
    [...langSelect.options].filter((o) => o.value === langOf(file)).forEach((o) => o.remove());
    main.current = null;
    refresh();
    open(main, DEFAULT_VARIANT);
  }

  function comparing() {
    return $('input[name="mode"][value="compare"]').checked;
  }

  function fillCompare() {
    const choices = variants().filter((f) => f !== main.current);
    const keep = compareSelect.value;
    compareSelect.replaceChildren(...choices.map((f) => new Option(f, f)));
    compareSelect.disabled = choices.length === 0;
    if (choices.includes(keep)) compareSelect.value = keep;
    if (comparing() && choices.length && other.current !== compareSelect.value) {
      open(other, compareSelect.value);
    }
  }

  function mode() {
    const compare = comparing();
    $('.preview-pane').hidden = compare;
    $('.compare-pane').hidden = !compare;
    if (compare) {
      fillCompare();
      if (compareSelect.value) open(other, compareSelect.value);
    } else {
      if (other.current) other.states[other.current] = other.view.state;
      other.current = null;
      if (live.checked) preview();
    }
  }

  function body(extra) {
    return { files: edited, delete: [...deleted], ...extra };
  }

  async function check() {
    const response = await post(`${api}/check`, body());
    const result = await response.json();
    if (!response.ok) {
      problems = [];
      show([{ detail: result.detail }], []);
    } else {
      problems = result.problems;
      show(result.problems, result.warnings);
    }
    showDiagnostics(main);
    showDiagnostics(other);
    if (live.checked && !comparing()) {
      if (response.ok && !result.problems.length) {
        preview();
      } else {
        previewState.textContent = 'Preview not updated: the edited files have problems.';
      }
    }
  }

  async function preview() {
    if (comparing()) return;
    const params = new URLSearchParams();
    for (const name of ['format', 'layout', 'lang']) {
      const select = $(`.preview-pane select[name="${name}"]`);
      if (select) params.set(name, select.value);
    }
    previewing?.abort();
    const request = new AbortController();
    previewing = request;
    previewState.textContent = 'Generating…';
    let response;
    try {
      response = await post(`${api}/preview?${params}`, body(), request.signal);
    } catch (e) {
      if (e.name === 'AbortError') return; // a newer preview took over
      previewState.textContent = `Preview failed: ${e.message}`;
      return;
    }
    if (!response.ok) {
      const result = await response.json();
      previewState.textContent = 'Preview not generated; see the problems.';
      show(result.errors || [{ detail: result.detail }], []);
      return;
    }
    const blob = await response.blob();
    if (request.signal.aborted) return;
    const frame = $('iframe[name="preview"]');
    if (frame.dataset.url) URL.revokeObjectURL(frame.dataset.url);
    frame.dataset.url = URL.createObjectURL(blob);
    frame.src = frame.dataset.url;
    previewState.textContent = changes()
      ? 'Showing the edited files, not saved.'
      : 'Showing the saved revision.';
  }

  async function saveRevision(force) {
    save.disabled = true;
    state.textContent = 'Checking and saving…';
    const response = await post(
      `/templates/${encodeURIComponent(id)}/revisions`,
      body(force === true ? { base, force: true } : { base }),
    );
    if (response.ok) {
      Object.keys(edited).forEach((f) => delete edited[f]);
      deleted.clear();
      window.location.href = `/ui/templates/${encodeURIComponent(id)}`;
      return;
    }
    const result = await response.json();
    if (response.status === 412 && String(result.type).endsWith(':revision-conflict')) {
      conflict(result.latestRevision);
      return;
    }
    state.textContent = 'Not saved: the checks found problems.';
    save.disabled = false;
    problems = result.errors || [];
    show(result.errors || [{ detail: result.detail }], []);
    showDiagnostics(main);
    showDiagnostics(other);
  }

  // Someone saved while these edits were open. Nothing was stored: the edits can be saved anyway
  // (a new revision from this editor's base; the other revision stays in the history), or
  // dropped for the latest revision.
  function conflict(latest) {
    state.textContent = 'Not saved: someone saved meanwhile.';
    save.disabled = false;
    messages.replaceChildren();
    const notice = document.createElement('p');
    notice.className = 'notice error';
    notice.textContent =
      `Revision ${latest} was saved while you were editing; your edits are based on revision ` +
      `${base}. Saving them anyway makes a new revision from revision ${base}, so the changes of ` +
      `revision ${latest} would not be in it (it stays in the history).`;
    const force = document.createElement('button');
    force.type = 'button';
    force.className = 'danger';
    force.textContent = 'Save anyway';
    force.addEventListener('click', () => saveRevision(true));
    const reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = `Open revision ${latest}`;
    reload.addEventListener('click', () => {
      if (confirm('Discard your edits and open the latest revision?')) {
        Object.keys(edited).forEach((f) => delete edited[f]);
        deleted.clear();
        window.location.reload();
      }
    });
    const actions = document.createElement('p');
    actions.className = 'bar';
    actions.append(force, reload);
    messages.append(notice, actions);
  }

  function post(url, payload, signal) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal,
    });
  }

  // "template.xhtml, line 12, column 5" or "template.json#/fields/total" or just a file.
  function place(location) {
    const match = /^([^,#( ]+)(?:, line (\d+), column (\d+))?/.exec(location || '');
    return match ? { file: match[1], line: Number(match[2] || 0), column: Number(match[3] || 0) } : null;
  }

  function showDiagnostics(ed) {
    if (!ed.current) return;
    const doc = ed.view.state.doc;
    const diagnostics = [];
    for (const problem of problems) {
      const at = place(problem.location);
      if (!at || at.file !== ed.current) continue;
      const line = doc.line(Math.min(Math.max(at.line, 1), doc.lines));
      const from = Math.min(line.from + Math.max(at.column - 1, 0), line.to);
      diagnostics.push({
        from,
        to: Math.max(from, Math.min(from + 1, line.to)),
        severity: 'error',
        message: problem.detail,
      });
    }
    ed.view.dispatch(setDiagnostics(ed.view.state, diagnostics));
  }

  function show(list, warnings) {
    messages.replaceChildren();
    if (!list.length && !warnings.length) return;
    const ul = document.createElement('ul');
    ul.className = 'problems';
    for (const problem of list) {
      const li = document.createElement('li');
      const at = place(problem.location);
      if (at && tabs().some((t) => t.dataset.file === at.file)) {
        const link = document.createElement('a');
        link.href = '#';
        link.textContent = problem.location;
        link.addEventListener('click', (e) => {
          e.preventDefault();
          open(main, at.file, at.line);
        });
        li.append(`${problem.detail} (`, link, ')');
      } else {
        li.textContent = problem.location ? `${problem.detail} (${problem.location})` : problem.detail;
      }
      ul.append(li);
    }
    for (const warning of warnings) {
      const li = document.createElement('li');
      li.className = 'muted';
      li.textContent = warning;
      ul.append(li);
    }
    const notice = document.createElement('p');
    notice.className = list.length ? 'notice error' : 'notice ok';
    notice.textContent = list.length ? `${list.length} problem(s)` : 'No problems.';
    messages.append(notice, ul);
  }
}
