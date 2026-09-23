--
-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 IT Beratung Hermann GmbH
--

-- A content revision lists the layout revisions it may be rendered with, from its
-- template.json ("layouts"); position 0 is the default. Like the columns it replaces, this is an
-- index of the stored files, never a second source: revisions stored so far named one layout
-- ("layout"), which is exactly what layout_id and layout_revision hold.

-- The old index has the name the new table takes (tables and indexes share a namespace).
drop index revision_layout;

create table revision_layout (
    template_id      varchar(64) not null,
    number           integer     not null,
    position         integer     not null,
    layout_id        varchar(64) not null,
    layout_revision  integer     not null,
    primary key (template_id, number, position),
    unique (template_id, number, layout_id),
    foreign key (template_id, number) references revision (template_id, number) on delete cascade
);

insert into revision_layout (template_id, number, position, layout_id, layout_revision)
select template_id, number, 0, layout_id, layout_revision
from revision
where layout_id is not null;

create index revision_layout_by_layout on revision_layout (layout_id, layout_revision);

alter table revision drop column layout_id, drop column layout_revision;
