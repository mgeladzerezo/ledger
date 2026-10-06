package io.github.mgeladzerezo.ledger.reconciliation;

/**
 * The invariants the reconciliation job verifies. Each check is one SQL query that returns a row
 * ({@code subject}, {@code detail}) per violation, so a clean ledger returns nothing.
 *
 * <p>These deliberately re-derive everything from the raw tables and trust nothing the
 * application maintains: this is the third, independent layer behind the domain model and the
 * database constraints, and the one that still works when somebody bypassed both with direct SQL.
 */
public enum ReconciliationCheck {

    GLOBAL_BALANCE(Severity.CRITICAL, "Total debits equal total credits in every currency", """
            select 'currency ' || currency as subject,
                   'debits ' || debits || ' <> credits ' || credits as detail
            from (select currency,
                         coalesce(sum(amount) filter (where side = 'DEBIT'), 0) as debits,
                         coalesce(sum(amount) filter (where side = 'CREDIT'), 0) as credits
                  from journal_entries
                  group by currency) totals
            where debits <> credits
            """),

    TRANSACTION_BALANCED(Severity.CRITICAL, "Every transaction's debits equal its credits per currency", """
            select 'transaction ' || transaction_id as subject,
                   currency || ': debits ' || debits || ' <> credits ' || credits as detail
            from (select transaction_id, currency,
                         coalesce(sum(amount) filter (where side = 'DEBIT'), 0) as debits,
                         coalesce(sum(amount) filter (where side = 'CREDIT'), 0) as credits
                  from journal_entries
                  group by transaction_id, currency) totals
            where debits <> credits
            limit 100
            """),

    TRANSACTION_HAS_ENTRIES(Severity.CRITICAL, "Every transaction has at least two entries", """
            select 'transaction ' || t.id as subject,
                   'has ' || count(e.id) || ' entries, at least 2 required' as detail
            from journal_transactions t
            left join journal_entries e on e.transaction_id = t.id
            group by t.id
            having count(e.id) < 2
            limit 100
            """),

    ORPHAN_ENTRIES(Severity.CRITICAL, "No entry without its transaction or account", """
            select 'entry ' || e.id as subject,
                   case when t.id is null then 'references missing transaction ' || e.transaction_id
                        else 'references missing account ' || e.account_id end as detail
            from journal_entries e
            left join journal_transactions t on t.id = e.transaction_id
            left join accounts a on a.id = e.account_id
            where t.id is null or a.id is null
            limit 100
            """),

    BALANCE_MATCHES_ENTRIES(Severity.CRITICAL, "Each materialised balance equals the sum of the account's entries", """
            select 'account ' || a.id || ' (' || a.code || ')' as subject,
                   'materialised balance ' || coalesce(b.balance::text, '<no balance row>')
                       || ' <> ' || coalesce(s.computed, 0) || ' computed from entries' as detail
            from accounts a
            left join account_balances b on b.account_id = a.id
            left join (select e.account_id,
                              sum(case when e.side = x.normal_side then e.amount else -e.amount end) as computed
                       from journal_entries e
                       join accounts x on x.id = e.account_id
                       group by e.account_id) s on s.account_id = a.id
            where b.account_id is null or b.balance <> coalesce(s.computed, 0)
            limit 100
            """),

    HELD_MATCHES_HOLDS(Severity.CRITICAL, "Each account's held amount equals the sum of its active holds", """
            select 'account ' || b.account_id as subject,
                   'held ' || b.held || ' <> ' || coalesce(h.total, 0) || ' sum of active holds' as detail
            from account_balances b
            left join (select account_id, sum(amount) as total
                       from holds
                       where status = 'ACTIVE'
                       group by account_id) h on h.account_id = b.account_id
            where b.held <> coalesce(h.total, 0)
            limit 100
            """),

    HOLDS_WITHIN_BALANCE(Severity.CRITICAL, "Holds never exceed the balance of an account that may not go negative", """
            select 'account ' || a.id || ' (' || a.code || ')' as subject,
                   'balance ' || b.balance || ' minus held ' || b.held || ' is below zero' as detail
            from accounts a
            join account_balances b on b.account_id = a.id
            where a.non_negative and b.balance - b.held < 0
            limit 100
            """),

    OUTBOX_COVERAGE(Severity.CRITICAL, "Every transaction has its outbox event", """
            select 'transaction ' || t.id as subject,
                   'no transaction.posted event in the outbox' as detail
            from journal_transactions t
            where not exists (select 1 from outbox_events e
                              where e.transaction_id = t.id and e.event_type = 'transaction.posted')
            limit 100
            """),

    IDEMPOTENCY_COMPLETE(Severity.CRITICAL, "Every committed idempotency key carries its stored response", """
            select 'idempotency key ' || scope || '/' || key as subject,
                   'committed without a stored response' as detail
            from idempotency_keys
            where status_code is null or response_body is null
            limit 100
            """),

    DEAD_DELIVERIES(Severity.WARNING, "No webhook delivery is dead-lettered", """
            select 'delivery ' || d.id as subject,
                   'event ' || d.event_id || ' gave up after ' || d.attempts || ' attempts: '
                       || coalesce(d.last_error, 'unknown error') as detail
            from outbox_deliveries d
            where d.status = 'DEAD'
            order by d.id desc
            limit 100
            """),

    STUCK_DELIVERIES(Severity.WARNING, "No webhook delivery has been pending for too long", """
            select 'delivery ' || d.id as subject,
                   'pending for ' || round(extract(epoch from now() - d.created_at)) || ' s after '
                       || d.attempts || ' attempts' as detail
            from outbox_deliveries d
            join webhook_subscriptions s on s.id = d.subscription_id
            where d.status = 'PENDING' and s.active
              and d.created_at < now() - (:stuckMillis * interval '1 millisecond')
            order by d.id
            limit 100
            """);

    private final Severity severity;
    private final String description;
    private final String sql;

    ReconciliationCheck(Severity severity, String description, String sql) {
        this.severity = severity;
        this.description = description;
        this.sql = sql;
    }

    public Severity severity() {
        return severity;
    }

    /** The invariant, phrased as the statement that should be true. */
    public String description() {
        return description;
    }

    String sql() {
        return sql;
    }

    /** CRITICAL means the books are wrong; WARNING means the books are right but something needs attention. */
    public enum Severity {
        CRITICAL,
        WARNING
    }
}
