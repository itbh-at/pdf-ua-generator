/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

// The code editor of the UI. It works only through the JSON API: it reads the files of the base
// revision, checks the edited files while typing (problems come from the server, the browser does
// not parse Qute), previews them without saving, and saves them as a new revision.
import { EditorView, basicSetup } from 'codemirror';
import { EditorState } from '@codemirror/state';
import { html } from '@codemirror/lang-html';
import { json } from '@codemirror/lang-json';
import { css } from '@codemirror/lang-css';
import { lintGutter, setDiagnostics } from '@codemirror/lint';

const root = document.getElementById('editor');
if (root) {
  start(root);
}

function start(root) {
  const id = root.dataset.id;
  const base = Number(root.dataset.base);
  const api = `/templates/${encodeURIComponent(id)}/revisions/${base}`;
  const original = {}; // path -> text of the base revision
  const edited = {}; // path -> text, only files that differ from the base
  const states = {}; // path -> editor state, so each file keeps its undo history
  let current = null;
  let problems = [];
  let timer = null;
  let previewing = null; // the running preview request, aborted when a newer one starts

  const view = new EditorView({ parent: root.querySelector('.code') });
  const tabs = [...root.querySelectorAll('.files [data-file]')];
  const state = root.querySelector('.state');
  const messages = root.querySelector('.messages');
  const save = root.querySelector('.save');
  const live = root.querySelector('input[name="live"]');
  const previewState = root.querySelector('.preview-state');

  tabs.forEach((tab) => tab.addEventListener('click', () => open(tab.dataset.file)));
  save.addEventListener('click', saveRevision);
  root.querySelector('.preview').addEventListener('click', preview);
  // The preview follows the choices at once, and the edits whenever they check out.
  root
    .querySelectorAll('.output select')
    .forEach((select) => select.addEventListener('change', preview));
  live.addEventListener('change', () => live.checked && preview());
  window.addEventListener('beforeunload', (e) => {
    if (Object.keys(edited).length) {
      e.preventDefault();
    }
  });
  if (tabs.length) {
    open(tabs[0].dataset.file);
  }
  preview();

  async function open(path, line) {
    if (current) {
      states[current] = view.state;
    }
    if (!(path in original)) {
      const response = await fetch(`${api}/files/${path.split('/').map(encodeURIComponent).join('/')}`);
      original[path] = response.ok ? await response.text() : '';
    }
    current = path;
    view.setState(
      states[path] ||
        EditorState.create({
          doc: edited[path] ?? original[path],
          extensions: [
            basicSetup,
            language(path),
            lintGutter(),
            EditorView.lineWrapping,
            EditorView.updateListener.of((update) => {
              if (update.docChanged) {
                changed(path, update.state.doc.toString());
              }
            }),
          ],
        }),
    );
    tabs.forEach((tab) => tab.setAttribute('aria-selected', String(tab.dataset.file === path)));
    showDiagnostics();
    if (line) {
      const target = view.state.doc.line(Math.min(line, view.state.doc.lines));
      view.dispatch({ selection: { anchor: target.from }, scrollIntoView: true });
      view.focus();
    }
  }

  function language(path) {
    if (path.endsWith('.json')) return json();
    if (path.endsWith('.css')) return css();
    if (path.endsWith('.xhtml')) return html();
    return [];
  }

  function changed(path, text) {
    if (text === original[path]) {
      delete edited[path];
    } else {
      edited[path] = text;
    }
    tabs.forEach((tab) => tab.classList.toggle('dirty', tab.dataset.file in edited));
    const count = Object.keys(edited).length;
    save.disabled = count === 0;
    state.textContent = count ? `${count} file(s) changed, not saved.` : 'No changes.';
    clearTimeout(timer);
    timer = setTimeout(check, 600);
  }

  async function check() {
    const response = await post(`${api}/check`, { files: edited });
    const body = await response.json();
    if (!response.ok) {
      problems = [];
      show([{ detail: body.detail }], []);
    } else {
      problems = body.problems;
      show(body.problems, body.warnings);
    }
    showDiagnostics();
    if (live.checked) {
      if (response.ok && !body.problems.length) {
        preview();
      } else {
        previewState.textContent = 'Preview not updated: the edited files have problems.';
      }
    }
  }

  async function preview() {
    const params = new URLSearchParams();
    for (const name of ['format', 'layout', 'lang']) {
      const select = root.querySelector(`select[name="${name}"]`);
      if (select) params.set(name, select.value);
    }
    previewing?.abort();
    const request = new AbortController();
    previewing = request;
    previewState.textContent = 'Generating…';
    let response;
    try {
      response = await post(`${api}/preview?${params}`, { files: edited }, request.signal);
    } catch (e) {
      if (e.name === 'AbortError') return; // a newer preview took over
      previewState.textContent = `Preview failed: ${e.message}`;
      return;
    }
    if (!response.ok) {
      const body = await response.json();
      previewState.textContent = 'Preview not generated; see the problems.';
      show(body.errors || [{ detail: body.detail }], []);
      return;
    }
    const blob = await response.blob();
    if (request.signal.aborted) return;
    const frame = root.querySelector('iframe[name="preview"]');
    if (frame.dataset.url) URL.revokeObjectURL(frame.dataset.url);
    frame.dataset.url = URL.createObjectURL(blob);
    frame.src = frame.dataset.url;
    previewState.textContent = Object.keys(edited).length
      ? 'Showing the edited files, not saved.'
      : 'Showing the saved revision.';
  }

  async function saveRevision() {
    save.disabled = true;
    state.textContent = 'Checking and saving…';
    const response = await post(`/templates/${encodeURIComponent(id)}/revisions`, {
      base,
      files: edited,
    });
    if (response.ok) {
      for (const path of Object.keys(edited)) delete edited[path];
      window.location.href = `/ui/templates/${encodeURIComponent(id)}`;
      return;
    }
    const body = await response.json();
    state.textContent = 'Not saved: the checks found problems.';
    save.disabled = false;
    problems = body.errors || [];
    show(body.errors || [{ detail: body.detail }], []);
    showDiagnostics();
  }

  function post(url, body, signal) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal,
    });
  }

  // "template.xhtml, line 12, column 5" or "template.json#/fields/total" or just a file.
  function place(location) {
    const match = /^([^,#( ]+)(?:, line (\d+), column (\d+))?/.exec(location || '');
    return match ? { file: match[1], line: Number(match[2] || 0), column: Number(match[3] || 0) } : null;
  }

  function showDiagnostics() {
    const doc = view.state.doc;
    const diagnostics = [];
    for (const problem of problems) {
      const at = place(problem.location);
      if (!at || at.file !== current) continue;
      const line = doc.line(Math.min(Math.max(at.line, 1), doc.lines));
      const from = Math.min(line.from + Math.max(at.column - 1, 0), line.to);
      diagnostics.push({ from, to: Math.max(from, Math.min(from + 1, line.to)), severity: 'error', message: problem.detail });
    }
    view.dispatch(setDiagnostics(view.state, diagnostics));
  }

  function show(list, warnings) {
    messages.replaceChildren();
    if (!list.length && !warnings.length) {
      return;
    }
    const ul = document.createElement('ul');
    ul.className = 'problems';
    for (const problem of list) {
      const li = document.createElement('li');
      const at = place(problem.location);
      if (at && tabs.some((tab) => tab.dataset.file === at.file)) {
        const link = document.createElement('a');
        link.href = '#';
        link.textContent = problem.location;
        link.addEventListener('click', (e) => {
          e.preventDefault();
          open(at.file, at.line);
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
