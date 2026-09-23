--
-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 IT Beratung Hermann GmbH
--

-- Lifecycle: a bundle is validated and released for use on upload; there is no
-- resting draft. A released revision is not deleted but archived (retired):
-- it existed and stays as history, but is no longer current. 'draft' remains
-- only as the transient state during an upload, promoted to 'published' or
-- removed within the same request.
alter table revision drop constraint revision_status_check;
alter table revision
    add constraint revision_status_check check (status in ('draft', 'published', 'archived'));

alter table revision add column archived_at timestamptz;
