--
-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 IT Beratung Hermann GmbH
--

-- A template is content (filled with data) or a layout (filled by content).
alter table template
    add column kind varchar(16) not null default 'content' check (kind in ('content', 'layout'));

-- The layout revision a content revision pins, from its template.json. No foreign key: a draft
-- may name a layout revision that does not exist yet; publishing checks it.
alter table revision
    add column layout_id varchar(64),
    add column layout_revision integer;

create index revision_layout on revision (layout_id, layout_revision);
