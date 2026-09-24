/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

// Qute in the code editor: highlighting of expressions, sections and comments, and completion of
// the names a template may use. The names come from the server (the vocabulary of the draft); the
// browser does not parse Qute, it only looks at the text around the cursor.
import { Decoration, MatchDecorator, ViewPlugin } from '@codemirror/view';
import { EditorState } from '@codemirror/state';

// {! comment !}, {#section …}, {/section}, {expression}; "{ " and "{}" are literal text.
const TAG = /\{!(?:[^!]|!(?!\}))*!\}|\{[#/][^{}\n]*\}|\{[A-Za-z_][^{}\n]*\}/g;

const decorator = new MatchDecorator({
  regexp: TAG,
  decoration: (match) =>
    Decoration.mark({
      class: match[0].startsWith('{!')
        ? 'cm-qute-comment'
        : /^\{[#/]/.test(match[0])
          ? 'cm-qute-section'
          : 'cm-qute-expression',
    }),
});

const highlighting = ViewPlugin.define(
  (view) => ({
    decorations: decorator.createDeco(view),
    update(update) {
      this.decorations = decorator.updateDeco(update, this.decorations);
    },
  }),
  { decorations: (plugin) => plugin.decorations },
);

// Sections of Qute itself; else, is and case continue a section instead of opening one.
const SECTIONS = [
  { label: 'if', detail: '{#if cond}…{/if}' },
  { label: 'else', detail: 'in {#if}' },
  { label: 'for', detail: '{#for item in list}…{/for}' },
  { label: 'each', detail: '{#each list}{it}{/each}' },
  { label: 'let', detail: '{#let name=value}…{/let}' },
  { label: 'when', detail: '{#when value}{#is …}…{/when}' },
  { label: 'is', detail: 'in {#when}' },
  { label: 'include', detail: '{#include layout}…{/include}' },
  { label: 'insert', detail: '{#insert area}…{/insert}' },
];
const CONTINUATIONS = new Set(['else', 'is', 'case']);

const FORMATTERS = {
  number: [
    { label: 'number', detail: 'grouped, e.g. 1,234.5' },
    { label: 'number(2)', detail: 'with two decimals' },
    { label: "currency('EUR')", detail: 'ISO 4217 currency' },
  ],
  date: [
    { label: 'date', detail: 'medium style' },
    { label: "date('long')", detail: 'short, medium, long or full' },
  ],
};

/**
 * Qute highlighting and completion for a template file.
 *
 * @param vocabulary a function returning the current vocabulary ({fields, areas, components,
 *     styles, texts}), or null while it is not known
 */
export function qute(vocabulary) {
  // One source object: CodeMirror tells running completions apart by the identity of the source.
  const data = [{ autocomplete: (context) => complete(context, vocabulary()) }];
  return [highlighting, EditorState.languageData.of(() => data)];
}

function complete(context, v) {
  if (!v) return null;
  const line = context.state.doc.lineAt(context.pos);
  const before = line.text.slice(0, context.pos - line.from);

  // class="…" on an element: the catalog styles of the layout.
  const cls = /\bclass="([^"{}]*)$/.exec(before);
  if (cls) {
    const word = /[\w-]*$/.exec(before)[0];
    return {
      from: context.pos - word.length,
      options: v.styles.map((s) => ({
        label: s.name,
        detail: s.kind,
        info: s.label,
        type: 'class',
      })),
      validFor: /^[\w-]*$/,
    };
  }

  const tag = /\{([#/]?)([^{}]*)$/.exec(before);
  if (!tag || /^!/.test(tag[2]) || (!tag[1] && /^\s/.test(tag[2]))) return null;
  const [, kind, content] = tag;
  const text = context.state.doc.sliceString(0, context.pos);
  const aliases = loopAliases(text, v);

  if (kind === '/') {
    // The innermost open section is the one to close.
    const open = openSections(text);
    const word = /[\w-]*$/.exec(content)[0];
    if (content !== word) return null;
    return {
      from: context.pos - word.length,
      options: open.reverse().map((name, i) => ({ label: name, apply: `${name}}`, boost: -i, type: 'keyword' })),
    };
  }

  if (kind === '#') {
    const loop = /^for\s+\w+\s+in\s+([\w.[\]]*)$/.exec(content) || /^each\s+([\w.[\]]*)$/.exec(content);
    if (loop) {
      // Lists, and the objects that may contain one.
      return fieldOptions(context, loop[1], v, aliases, (f) => f.type === 'list' || f.type === 'object');
    }
    if (/^[\w-]*$/.test(content)) {
      const own = [
        ...v.areas.map((a) => ({
          label: a.name,
          detail: a.required ? 'area, required' : 'area',
          info: a.label,
          type: 'namespace',
          apply: `${a.name}}`,
          boost: 2,
        })),
        ...v.components.map((c) => ({
          label: c.name,
          detail: 'component',
          info: [c.label, c.parameters.length ? `Parameters: ${c.parameters.join(', ')}` : '']
            .filter(Boolean)
            .join('\n'),
          type: 'function',
          boost: 1,
          apply: c.required.length
            ? `${c.name} ${c.required.map((p) => `${p}=""`).join(' ')}`
            : c.name,
        })),
      ];
      return {
        from: context.pos - content.length,
        options: [...own, ...SECTIONS.map((s) => ({ ...s, type: 'keyword' }))],
        validFor: /^[\w-]*$/,
      };
    }
    // Arguments of a section: {#if total} {#let x=order.total} {#box title=customer.name}.
    const argument = /[\w.[\]]*$/.exec(content)[0];
    if (/[\s=]$|[\s=][\w.[\]]+$/.test(content)) {
      return fieldOptions(context, argument, v, aliases, () => true);
    }
    return null;
  }

  // An expression: {msg:key}, {field.path}, {field.format}.
  const msg = /^msg:([\w.-]*)$/.exec(content);
  if (msg) {
    return {
      from: context.pos - msg[1].length,
      options: v.texts.map((t) => ({ label: t, type: 'text' })),
      validFor: /^[\w.-]*$/,
    };
  }
  if (!/^[\w.[\]]*$/.test(content)) return null;
  const result = fieldOptions(context, content, v, aliases, () => true);
  if (result && !content.includes('.') && v.texts.length) {
    result.options.push({ label: 'msg:', detail: 'a text of the layout', type: 'namespace' });
  }
  return result;
}

// The fields at the path typed so far: the children of the part before the last dot, or the
// formatting functions if that part is a number or a date.
function fieldOptions(context, typed, v, aliases, accept) {
  const dot = typed.lastIndexOf('.');
  const head = dot < 0 ? '' : typed.slice(0, dot);
  const word = typed.slice(dot + 1);
  const from = context.pos - word.length;
  let options;
  if (!head) {
    options = [
      ...Object.entries(aliases).map(([name, path]) => ({
        label: name,
        detail: `item of ${path.slice(0, -2)}`,
        type: 'variable',
      })),
      ...children(v, '').filter(accept).map(option),
    ];
  } else {
    const path = resolve(head, aliases);
    const field = v.fields.find((f) => f.path === path);
    if (field && FORMATTERS[field.type]) {
      options = FORMATTERS[field.type].map((f) => ({ ...f, type: 'method' }));
    } else {
      options = children(v, `${path}.`).filter(accept).map(option);
    }
  }
  return { from, options, validFor: /^\w*$/ };
}

function children(v, prefix) {
  return v.fields.filter((f) => {
    if (!f.path.startsWith(prefix)) return false;
    const rest = f.path.slice(prefix.length);
    return rest && !/[.[]/.test(rest);
  });
}

function option(field) {
  return {
    label: field.path.slice(field.path.lastIndexOf('.') + 1),
    detail: field.type,
    info: [field.label, field.description].filter(Boolean).join('\n') || undefined,
    type: 'property',
  };
}

// "item.price" with {#for item in order.positions} → "order.positions[].price".
function resolve(expression, aliases) {
  const [first, ...rest] = expression.split('.');
  return [aliases[first] ?? first, ...rest].join('.');
}

// The loop variables before the cursor: name → path of the list's items ("order.positions[]").
function loopAliases(text, v) {
  const aliases = {};
  const loops = /\{#for\s+(\w+)\s+in\s+([\w.]+)|\{#each\s+([\w.]+)/g;
  for (const m of text.matchAll(loops)) {
    const name = m[1] ?? 'it';
    const list = resolve(m[2] ?? m[3], aliases);
    if (v.fields.some((f) => f.path === `${list}[]`)) aliases[name] = `${list}[]`;
  }
  return aliases;
}

// The names of the sections open at the end of the text, outermost first.
function openSections(text) {
  const open = [];
  const tags = /\{(#|\/)([\w-]+)[^{}]*?(\/)?\}/g;
  for (const [, kind, name, closed] of text.matchAll(tags)) {
    if (kind === '#' && !closed && !CONTINUATIONS.has(name)) {
      open.push(name);
    } else if (kind === '/') {
      const at = open.lastIndexOf(name);
      if (at >= 0) open.splice(at);
    }
  }
  return open;
}
