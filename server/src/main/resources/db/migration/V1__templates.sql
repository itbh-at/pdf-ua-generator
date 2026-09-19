--
-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 IT Beratung Hermann GmbH
--

-- Every file of every revision, stored once per content hash.
create table asset (
    sha256      char(64)     primary key,
    media_type  varchar(100) not null,
    size        bigint       not null,
    content     bytea        not null,
    created_at  timestamptz  not null default now()
);

create table template (
    id          varchar(64)  primary key,
    created_at  timestamptz  not null default now()
);

-- Immutable revisions; a draft becomes published once and stays so.
create table revision (
    template_id  varchar(64)  not null references template (id) on delete cascade,
    number       integer      not null,
    status       varchar(16)  not null check (status in ('draft', 'published')),
    sha256       char(64)     not null,
    created_at   timestamptz  not null default now(),
    published_at timestamptz,
    primary key (template_id, number)
);

create index revision_published on revision (template_id, number) where status = 'published';

create table revision_file (
    template_id   varchar(64)  not null,
    number        integer      not null,
    path          varchar(512) not null,
    asset_sha256  char(64)     not null references asset (sha256),
    primary key (template_id, number, path),
    foreign key (template_id, number) references revision (template_id, number) on delete cascade
);
