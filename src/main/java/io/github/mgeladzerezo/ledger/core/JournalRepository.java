package io.github.mgeladzerezo.ledger.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.mgeladzerezo.ledger.domain.EntryLine;
import io.github.mgeladzerezo.ledger.domain.Posting;
import io.github.mgeladzerezo.ledger.domain.Side;
import io.github.mgeladzerezo.ledger.domain.TransactionKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL for the append-only journal. There is no update or delete here, by design. */
@Repository
public class JournalRepository {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private static final String SELECT_TX = """
            select t.id, t.seq, t.kind, t.description, t.reverses_id, r.id as reversed_by, t.idempotency_key,
                   t.created_by, t.metadata::text as metadata, t.created_at
            from journal_transactions t
            left join journal_transactions r on r.reverses_id = t.id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JournalRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertTransaction(UUID id, Posting posting, Caller caller) {
        jdbc.sql("""
                        insert into journal_transactions (id, kind, description, reverses_id, idempotency_key,
                                                          created_by, metadata)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb)
                        """)
                .params(id, posting.kind().name(), posting.description(), posting.reversesId(),
                        caller.idempotencyKey(), caller.principal(), json.writeValueAsString(posting.metadata()))
                .update();
    }

    public void insertEntry(UUID transactionId, int lineNo, EntryLine line) {
        jdbc.sql("""
                        insert into journal_entries (transaction_id, line_no, account_id, side, amount, currency)
                        values (?, ?, ?, ?, ?, ?)
                        """)
                .params(transactionId, lineNo, line.accountId(), line.side().name(), line.amount(), line.currency())
                .update();
    }

    public boolean isReversed(UUID transactionId) {
        return jdbc.sql("select exists (select 1 from journal_transactions where reverses_id = ?)")
                .param(transactionId)
                .query(Boolean.class)
                .single();
    }

    public Optional<JournalTransaction> find(UUID id) {
        List<JournalTransaction> found = withEntries(
                jdbc.sql(SELECT_TX + " where t.id = ?").param(id).query(this::mapHeader).list());
        return found.stream().findFirst();
    }

    /**
     * Newest first. Keyset pagination on {@code seq}: pass the last seen seq to continue, so the
     * query stays an index range scan however deep the client pages.
     *
     * @param beforeSeq exclusive upper bound, or {@code Long.MAX_VALUE} for the first page
     * @param accountId restrict to transactions touching this account, or {@code null}
     */
    public List<JournalTransaction> list(long beforeSeq, int limit, Long accountId) {
        String filter = accountId == null ? "" : """
                 and exists (select 1 from journal_entries e where e.transaction_id = t.id and e.account_id = :account)
                """;
        var statement = jdbc.sql(SELECT_TX + " where t.seq < :before" + filter + " order by t.seq desc limit :limit")
                .param("before", beforeSeq)
                .param("limit", limit);
        if (accountId != null) {
            statement = statement.param("account", accountId);
        }
        return withEntries(statement.query(this::mapHeader).list());
    }

    /** One account's entries, newest first, by keyset on the entry id. */
    public List<AccountEntry> entriesForAccount(long accountId, long beforeId, int limit) {
        return jdbc.sql("""
                        select e.id, e.transaction_id, e.line_no, e.account_id, e.side, e.amount, e.currency,
                               e.created_at, t.kind, t.description
                        from journal_entries e
                        join journal_transactions t on t.id = e.transaction_id
                        where e.account_id = ? and e.id < ?
                        order by e.id desc
                        limit ?
                        """)
                .params(accountId, beforeId, limit)
                .query((rs, rowNum) -> new AccountEntry(mapEntry(rs), TransactionKind.valueOf(rs.getString("kind")),
                        rs.getString("description")))
                .list();
    }

    public long countTransactions() {
        return jdbc.sql("select count(*) from journal_transactions").query(Long.class).single();
    }

    private List<JournalTransaction> withEntries(List<JournalTransaction> headers) {
        if (headers.isEmpty()) {
            return headers;
        }
        Map<UUID, List<JournalEntry>> entries = new LinkedHashMap<>();
        headers.forEach(header -> entries.put(header.id(), new ArrayList<>()));
        jdbc.sql("""
                        select id, transaction_id, line_no, account_id, side, amount, currency, created_at
                        from journal_entries
                        where transaction_id in (:ids)
                        order by transaction_id, line_no
                        """)
                .param("ids", entries.keySet())
                .query((rs, rowNum) -> mapEntry(rs))
                .list()
                .forEach(entry -> entries.get(entry.transactionId()).add(entry));
        return headers.stream()
                .map(h -> new JournalTransaction(h.id(), h.seq(), h.kind(), h.description(), h.reversesId(),
                        h.reversedBy(), h.idempotencyKey(), h.createdBy(), h.metadata(), h.createdAt(),
                        List.copyOf(entries.get(h.id()))))
                .toList();
    }

    private JournalTransaction mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new JournalTransaction(
                Rows.uuid(rs, "id"),
                rs.getLong("seq"),
                TransactionKind.valueOf(rs.getString("kind")),
                rs.getString("description"),
                Rows.uuid(rs, "reverses_id"),
                Rows.uuid(rs, "reversed_by"),
                rs.getString("idempotency_key"),
                rs.getString("created_by"),
                json.readValue(rs.getString("metadata"), STRING_MAP),
                Rows.instant(rs, "created_at"),
                List.of());
    }

    private static JournalEntry mapEntry(ResultSet rs) throws SQLException {
        return new JournalEntry(
                rs.getLong("id"),
                Rows.uuid(rs, "transaction_id"),
                rs.getInt("line_no"),
                rs.getLong("account_id"),
                Side.valueOf(rs.getString("side")),
                rs.getLong("amount"),
                rs.getString("currency"),
                Rows.instant(rs, "created_at"));
    }

    /** An entry with the kind and description of the transaction it belongs to, for account history. */
    public record AccountEntry(JournalEntry entry, TransactionKind kind, String description) {
    }
}
