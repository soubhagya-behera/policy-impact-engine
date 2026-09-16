-- Policy Impact Engine
-- V4: privacy concept vocabulary and deterministic concept matches (Phase 2N).
-- Append-only: concepts and matches are never updated or deleted; vocabulary
-- extensions arrive as new migrations, matches are immutable history.
-- Non-destructive: does not alter V1 (policy), V2 (policy_version) or V3 (policy_change).

-- Vocabulary: database-backed privacy concepts, extensible via migrations.
CREATE TABLE privacy_concept (
    id                  uuid        PRIMARY KEY,
    code                varchar(64) NOT NULL,
    label               varchar(255) NOT NULL,
    description         text        NOT NULL,
    default_weight      integer     NOT NULL CHECK (default_weight >= 0),
    default_sensitivity integer     NOT NULL CHECK (default_sensitivity >= 0 AND default_sensitivity <= 5),
    created_at          timestamptz NOT NULL,
    CONSTRAINT uq_privacy_concept_code UNIQUE (code)
);

-- Matches: immutable evidence that a policy change relates to a concept.
CREATE TABLE change_concept_match (
    id              uuid         PRIMARY KEY,
    change_id       uuid         NOT NULL REFERENCES policy_change (id),
    concept_id      uuid         NOT NULL REFERENCES privacy_concept (id),
    matched_fragment text        NOT NULL CHECK (char_length(matched_fragment) > 0),
    pattern_id      varchar(128) NOT NULL,
    match_kind      varchar(32)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    CONSTRAINT uq_change_concept_match UNIQUE (change_id, concept_id)
);

CREATE INDEX idx_change_concept_match_change ON change_concept_match (change_id);
CREATE INDEX idx_change_concept_match_concept ON change_concept_match (concept_id);

-- Seed vocabulary: 9 initial concepts covering the differentiator examples
-- (ARCHITECTURE.md section 17). Fixed UUIDs for reviewability and
-- deterministic tests; no extension required.

INSERT INTO privacy_concept (id, code, label, description, default_weight, default_sensitivity, created_at) VALUES
('11111111-1111-1111-1111-111111111001', 'LOCATION', 'Location', 'Collection, use or sharing of location or geolocation data including precise and coarse location, GPS, latitude and longitude.', 8, 3, now()),
('11111111-1111-1111-1111-111111111002', 'THIRD_PARTY_SHARING', 'Third-party sharing', 'Sharing, sale, disclosure or transfer of personal data to third parties, partners, affiliates or service providers.', 10, 4, now()),
('11111111-1111-1111-1111-111111111003', 'ADVERTISING', 'Advertising', 'Use of personal data for advertising, marketing, targeted or personalised ads, ad networks and ad targeting.', 7, 3, now()),
('11111111-1111-1111-1111-111111111004', 'DATA_RETENTION', 'Data retention', 'How long personal data is retained, stored or kept, retention periods and storage duration.', 6, 2, now()),
('11111111-1111-1111-1111-111111111005', 'DELETION_RIGHTS', 'Deletion rights', 'Rights to delete, erase or remove personal data, right to be forgotten and account deletion.', 8, 3, now()),
('11111111-1111-1111-1111-111111111006', 'COOKIES', 'Cookies', 'Use of cookies, tracking pixels, web beacons and similar local storage technologies.', 4, 2, now()),
('11111111-1111-1111-1111-111111111007', 'CHILDREN_DATA', 'Children''s data', 'Collection or processing of children''s personal data, COPPA, parental consent and age thresholds for minors.', 9, 4, now()),
('11111111-1111-1111-1111-111111111008', 'ARBITRATION', 'Arbitration', 'Dispute resolution terms including binding arbitration, class action waiver, jury waiver and governing law.', 5, 2, now()),
('11111111-1111-1111-1111-111111111009', 'LEGAL_BASIS', 'Legal basis', 'Legal basis for processing personal data such as consent, legitimate interest, contract and legal obligation.', 5, 2, now());
