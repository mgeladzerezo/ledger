package io.github.mgeladzerezo.ledger.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

import io.github.mgeladzerezo.ledger.domain.AccountStatus;
import io.github.mgeladzerezo.ledger.domain.AccountType;
import io.github.mgeladzerezo.ledger.domain.Side;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for accounts and their materialised balance rows. */
@Repository
public class AccountRepository {

    private static final String SELECT = """
            select a.id, a.code, a.name, a.type, a.normal_side, a.currency, a.status, a.non_negative, a.system,
                   a.created_at, b.balance, b.held, b.version, b.updated_at
            from accounts a
            join account_balances b on b.account_id = a.id
            """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the account and its zero balance row.
     *
     * @return the new id, or empty if {@code code} is already taken
     */
    public Optional<Long> insert(String code, String name, AccountType type, String currency, boolean nonNegative,
                                 boolean system) {
        Optional<Long> id = jdbc.sql("""
                        insert into accounts (code, name, type, normal_side, currency, non_negative, system)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (code) do nothing
                        returning id
                        """)
                .params(code, name, type.name(), type.normalSide().name(), currency, nonNegative, system)
                .query(Long.class)
                .optional();
        id.ifPresent(accountId -> jdbc
                .sql("insert into account_balances (account_id, non_negative) values (?, ?)")
                .params(accountId, nonNegative)
                .update());
        return id;
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql(SELECT + " where a.id = ?").param(id).query(AccountRepository::map).optional();
    }

    public Optional<Account> findByCode(String code) {
        return jdbc.sql(SELECT + " where a.code = ?").param(code).query(AccountRepository::map).optional();
    }

    /** Accounts with id greater than {@code afterId}, ascending. */
    public List<Account> list(long afterId, int limit) {
        return jdbc.sql(SELECT + " where a.id > ? order by a.id limit ?")
                .params(afterId, limit)
                .query(AccountRepository::map)
                .list();
    }

    /**
     * Takes the balance row lock of every listed account, <strong>in ascending id order</strong>,
     * and returns the accounts as they are once the locks are held.
     *
     * <p>This is the deadlock-avoidance rule of the whole ledger. A transfer A to B and a
     * simultaneous transfer B to A both lock min(A, B) first, so one simply queues behind the
     * other; neither can hold one lock while waiting for the other's. The sorted set in the
     * signature makes the ordering a compile-time property of every caller.
     *
     * <p>Locking and reading are two statements on purpose. Under READ COMMITTED a statement that
     * blocks on a row lock re-reads only the locked row when it wakes up; columns joined in from
     * {@code accounts} would still come from the snapshot taken before the wait and could show a
     * status that has since changed. The second statement runs on a fresh snapshot while the
     * locks pin the balances.
     */
    public Map<Long, Account> lockInOrder(SortedSet<Long> accountIds) {
        jdbc.sql("select account_id from account_balances where account_id in (:ids) order by account_id for update")
                .param("ids", accountIds)
                .query(Long.class)
                .list();
        return findByIds(accountIds);
    }

    public Map<Long, Account> findByIds(Collection<Long> accountIds) {
        Map<Long, Account> byId = new LinkedHashMap<>();
        if (accountIds.isEmpty()) {
            return byId;
        }
        jdbc.sql(SELECT + " where a.id in (:ids) order by a.id")
                .param("ids", accountIds)
                .query(AccountRepository::map)
                .list()
                .forEach(account -> byId.put(account.id(), account));
        return byId;
    }

    /** Adjusts the materialised balance and hold total. The caller must hold the row lock. */
    public void applyDelta(long accountId, long balanceDelta, long heldDelta) {
        jdbc.sql("""
                        update account_balances
                        set balance = balance + ?, held = held + ?, version = version + 1, updated_at = now()
                        where account_id = ?
                        """)
                .params(balanceDelta, heldDelta, accountId)
                .update();
    }

    /** Overwrites the materialised balance and hold total with recomputed values. */
    public void overwriteBalance(long accountId, long balance, long held) {
        jdbc.sql("""
                        update account_balances
                        set balance = ?, held = ?, version = version + 1, updated_at = now()
                        where account_id = ?
                        """)
                .params(balance, held, accountId)
                .update();
    }

    public void updateStatus(long accountId, AccountStatus status) {
        jdbc.sql("update accounts set status = ? where id = ?").params(status.name(), accountId).update();
    }

    /** The balance implied by the journal alone: the sum of the account's entries on its normal side. */
    public long balanceFromEntries(long accountId) {
        return jdbc.sql("""
                        select coalesce(sum(case when e.side = a.normal_side then e.amount else -e.amount end), 0)
                        from journal_entries e
                        join accounts a on a.id = e.account_id
                        where e.account_id = ?
                        """)
                .param(accountId)
                .query(Long.class)
                .single();
    }

    private static Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                Side.valueOf(rs.getString("normal_side")),
                rs.getString("currency"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getBoolean("non_negative"),
                rs.getBoolean("system"),
                Rows.instant(rs, "created_at"),
                rs.getLong("balance"),
                rs.getLong("held"),
                rs.getLong("version"),
                Rows.instant(rs, "updated_at"));
    }
}
