-- Core double-entry schema.
--
-- Invariants enforced here, independently of the application code:
--   * an entry's currency is its account's currency (composite foreign key);
--   * a committed transaction has at least two entries and its debits equal its credits per
--     currency (deferred constraint trigger, checked at COMMIT);
--   * journal rows are append-only (UPDATE, DELETE and TRUNCATE raise);
--   * an account flagged non_negative can never have balance - held < 0 (CHECK constraint).

create table accounts (
    id           bigint generated always as identity primary key,
    code         text        not null unique,
    name         text        not null,
    type         text        not null check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    normal_side  text        not null check (normal_side in ('DEBIT', 'CREDIT')),
    currency     text        not null check (currency ~ '^[A-Z]{3}$'),
    status       text        not null default 'OPEN' check (status in ('OPEN', 'FROZEN', 'CLOSED')),
    non_negative boolean     not null default false,
    system       boolean     not null default false,
    created_at   timestamptz not null default now(),
    -- target of the composite foreign keys that pin an entry's currency to its account's currency
    unique (id, currency),
    check ((type in ('ASSET', 'EXPENSE')) = (normal_side = 'DEBIT'))
);

-- The materialised balance. One row per account, updated in the same database transaction as
-- the entries it summarises, always while holding its row lock (see LedgerPoster).
-- "balance" is expressed on the account's normal side: positive means the usual direction.
create table account_balances (
    account_id   bigint      primary key references accounts (id),
    balance      bigint      not null default 0,
    held         bigint      not null default 0 check (held >= 0),
    non_negative boolean     not null,
    version      bigint      not null default 0,
    updated_at   timestamptz not null default now(),
    -- last line of defence against an overdraft: even a buggy code path cannot commit one
    constraint account_balances_no_overdraft check (not non_negative or balance - held >= 0)
);

create table journal_transactions (
    id              uuid        primary key,
    seq             bigint      generated always as identity unique,
    kind            text        not null,
    description     text        not null default '',
    reverses_id     uuid        references journal_transactions (id),
    idempotency_key text,
    created_by      text        not null,
    metadata        jsonb       not null default '{}'::jsonb,
    created_at      timestamptz not null default now()
);

-- a transaction can be reversed at most once
create unique index journal_transactions_reverses_uq on journal_transactions (reverses_id)
    where reverses_id is not null;
create index journal_transactions_idem_idx on journal_transactions (created_by, idempotency_key)
    where idempotency_key is not null;

create table journal_entries (
    id             bigint generated always as identity primary key,
    transaction_id uuid        not null references journal_transactions (id),
    line_no        int         not null,
    account_id     bigint      not null,
    side           text        not null check (side in ('DEBIT', 'CREDIT')),
    amount         bigint      not null check (amount > 0),
    currency       text        not null,
    created_at     timestamptz not null default now(),
    unique (transaction_id, line_no),
    foreign key (account_id, currency) references accounts (id, currency)
);

create index journal_entries_account_idx on journal_entries (account_id, id);

-- ---------------------------------------------------------------------------------------------
-- Layer 2 of "debits equal credits": a deferred constraint trigger.
-- Deferred because a transaction is unbalanced after its first entry by construction; the check
-- has to see the whole transaction, so it runs at COMMIT. If it raises, the COMMIT fails and
-- nothing is persisted.
-- ---------------------------------------------------------------------------------------------
create function ledger_assert_balanced(p_tx uuid) returns void
    language plpgsql as
$$
declare
    v_count    int;
    v_currency text;
    v_debits   numeric;
    v_credits  numeric;
begin
    select count(*) into v_count from journal_entries where transaction_id = p_tx;
    if v_count < 2 then
        raise exception 'journal transaction % has % entries, at least 2 are required', p_tx, v_count
            using errcode = 'check_violation', constraint = 'journal_transaction_min_entries';
    end if;

    select e.currency,
           coalesce(sum(e.amount) filter (where e.side = 'DEBIT'), 0),
           coalesce(sum(e.amount) filter (where e.side = 'CREDIT'), 0)
    into v_currency, v_debits, v_credits
    from journal_entries e
    where e.transaction_id = p_tx
    group by e.currency
    having coalesce(sum(e.amount) filter (where e.side = 'DEBIT'), 0)
        <> coalesce(sum(e.amount) filter (where e.side = 'CREDIT'), 0)
    limit 1;

    if found then
        raise exception 'journal transaction % is unbalanced in %: debits % <> credits %',
            p_tx, v_currency, v_debits, v_credits
            using errcode = 'check_violation', constraint = 'journal_transaction_balanced';
    end if;
end
$$;

create function ledger_entry_balanced_trg() returns trigger
    language plpgsql as
$$
begin
    perform ledger_assert_balanced(new.transaction_id);
    return null;
end
$$;

create function ledger_transaction_balanced_trg() returns trigger
    language plpgsql as
$$
begin
    perform ledger_assert_balanced(new.id);
    return null;
end
$$;

create constraint trigger journal_entries_balanced
    after insert on journal_entries
    deferrable initially deferred
    for each row execute function ledger_entry_balanced_trg();

-- catches a transaction header committed without any entries
create constraint trigger journal_transactions_balanced
    after insert on journal_transactions
    deferrable initially deferred
    for each row execute function ledger_transaction_balanced_trg();

-- ---------------------------------------------------------------------------------------------
-- Append-only journal. Corrections are new, reversing transactions; history is never edited.
-- A trigger (rather than only REVOKE) so the rule also binds the table owner.
-- ---------------------------------------------------------------------------------------------
create function ledger_forbid_mutation() returns trigger
    language plpgsql as
$$
begin
    raise exception '% on % is forbidden: the journal is append-only', tg_op, tg_table_name
        using errcode = 'restrict_violation';
end
$$;

create trigger journal_entries_immutable
    before update or delete on journal_entries
    for each row execute function ledger_forbid_mutation();
create trigger journal_entries_no_truncate
    before truncate on journal_entries
    for each statement execute function ledger_forbid_mutation();
create trigger journal_transactions_immutable
    before update or delete on journal_transactions
    for each row execute function ledger_forbid_mutation();
create trigger journal_transactions_no_truncate
    before truncate on journal_transactions
    for each statement execute function ledger_forbid_mutation();

-- ---------------------------------------------------------------------------------------------
-- Holds (authorisations). A hold reserves part of the available balance without posting
-- entries; capture posts the real transfer and releases the reservation in one transaction.
-- ---------------------------------------------------------------------------------------------
create table holds (
    id                     uuid        primary key,
    account_id             bigint      not null,
    currency               text        not null,
    amount                 bigint      not null check (amount > 0),
    status                 text        not null check (status in ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    captured_amount        bigint      not null default 0,
    capture_transaction_id uuid        references journal_transactions (id),
    description            text        not null default '',
    created_by             text        not null,
    created_at             timestamptz not null default now(),
    expires_at             timestamptz not null,
    resolved_at            timestamptz,
    foreign key (account_id, currency) references accounts (id, currency),
    check (captured_amount between 0 and amount),
    check ((status = 'CAPTURED') = (capture_transaction_id is not null))
);

create index holds_active_idx on holds (account_id) where status = 'ACTIVE';
create index holds_expiry_idx on holds (expires_at) where status = 'ACTIVE';

-- ---------------------------------------------------------------------------------------------
-- Idempotency keys. There is deliberately no "in progress" state column: a request in flight is
-- an uncommitted row. A second request with the same key blocks on the primary key until the
-- first commits (then replays its response) or rolls back / crashes (then runs itself).
-- ---------------------------------------------------------------------------------------------
create table idempotency_keys (
    scope         text        not null,
    key           text        not null,
    operation     text        not null,
    request_hash  text        not null,
    status_code   int,
    response_body text,
    created_at    timestamptz not null default now(),
    expires_at    timestamptz not null,
    primary key (scope, key)
);

create index idempotency_keys_expiry_idx on idempotency_keys (expires_at);

-- ---------------------------------------------------------------------------------------------
-- Transactional outbox and webhook deliveries.
-- ---------------------------------------------------------------------------------------------
create table outbox_events (
    id             uuid        primary key,
    seq            bigint      generated always as identity unique,
    event_type     text        not null,
    transaction_id uuid        references journal_transactions (id),
    payload        jsonb       not null,
    created_at     timestamptz not null default now()
);

create index outbox_events_tx_idx on outbox_events (transaction_id) where transaction_id is not null;

create table webhook_subscriptions (
    id         bigint generated always as identity primary key,
    url        text        not null,
    secret     text        not null,
    active     boolean     not null default true,
    created_at timestamptz not null default now()
);

create table outbox_deliveries (
    id              bigint generated always as identity primary key,
    event_id        uuid        not null references outbox_events (id),
    subscription_id bigint      not null references webhook_subscriptions (id),
    status          text        not null default 'PENDING' check (status in ('PENDING', 'SENT', 'DEAD')),
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_status     int,
    last_error      text,
    created_at      timestamptz not null default now(),
    sent_at         timestamptz,
    unique (event_id, subscription_id)
);

-- the relay's claim query is an index scan over exactly the rows that are due
create index outbox_deliveries_due_idx on outbox_deliveries (next_attempt_at, id) where status = 'PENDING';
create index outbox_deliveries_event_idx on outbox_deliveries (event_id);

-- ---------------------------------------------------------------------------------------------
-- Reconciliation reports.
-- ---------------------------------------------------------------------------------------------
create table reconciliation_runs (
    id            bigint generated always as identity primary key,
    trigger       text        not null check (trigger in ('SCHEDULED', 'MANUAL')),
    status        text        not null check (status in ('CLEAN', 'WARNINGS', 'FAILED')),
    started_at    timestamptz not null,
    finished_at   timestamptz not null,
    checks_run    int         not null,
    finding_count int         not null,
    summary       jsonb       not null default '{}'::jsonb
);

create table reconciliation_findings (
    id         bigint generated always as identity primary key,
    run_id     bigint not null references reconciliation_runs (id) on delete cascade,
    check_name text   not null,
    severity   text   not null check (severity in ('CRITICAL', 'WARNING')),
    subject    text   not null,
    detail     text   not null
);

create index reconciliation_findings_run_idx on reconciliation_findings (run_id);
