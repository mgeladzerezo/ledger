package io.github.mgeladzerezo.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Layer one of "debits equal credits": an unbalanced posting cannot be constructed at all. */
class PostingTest {

    @Test
    void balancedTwoLinePostingIsAccepted() {
        Posting posting = Posting.simple(TransactionKind.TRANSFER, "rent", 1, 2, 500, "USD");

        assertThat(posting.lines()).containsExactly(EntryLine.debit(1, 500, "USD"), EntryLine.credit(2, 500, "USD"));
    }

    @Test
    void debitsNotEqualToCreditsAreRejected() {
        assertThatThrownBy(() -> new Posting(TransactionKind.MULTI_LEG, "broken",
                List.of(EntryLine.debit(1, 500, "USD"), EntryLine.credit(2, 499, "USD"))))
                .isInstanceOf(LedgerException.InvalidPosting.class)
                .hasMessageContaining("differ by 1 in USD");
    }

    @Test
    void balanceIsRequiredPerCurrencyNotInTotal() {
        // 100 USD against 100 EUR nets to zero as plain numbers but is nonsense as accounting
        assertThatThrownBy(() -> new Posting(TransactionKind.MULTI_LEG, "cross-currency",
                List.of(EntryLine.debit(1, 100, "USD"), EntryLine.credit(2, 100, "EUR"))))
                .isInstanceOf(LedgerException.InvalidPosting.class);
    }

    @Test
    void multiLegAndMultiCurrencyPostingsBalanceIndependently() {
        Posting fx = new Posting(TransactionKind.FX_TRANSFER, "fx", List.of(
                EntryLine.debit(1, 1000, "USD"), EntryLine.credit(2, 1000, "USD"),
                EntryLine.debit(3, 920, "EUR"), EntryLine.credit(4, 920, "EUR")));
        Posting split = new Posting(TransactionKind.MULTI_LEG, "split", List.of(
                EntryLine.debit(1, 1000, "USD"), EntryLine.credit(2, 985, "USD"), EntryLine.credit(3, 15, "USD")));

        assertThat(fx.lines()).hasSize(4);
        assertThat(split.lines()).hasSize(3);
    }

    @Test
    void singleEntryIsRejected() {
        assertThatThrownBy(() -> new Posting(TransactionKind.MULTI_LEG, "lonely", List.of(EntryLine.debit(1, 5, "USD"))))
                .isInstanceOf(LedgerException.InvalidPosting.class)
                .hasMessageContaining("at least two entries");
    }

    @Test
    void zeroNegativeAndMalformedLinesAreRejected() {
        assertThatThrownBy(() -> EntryLine.debit(1, 0, "USD")).isInstanceOf(LedgerException.InvalidPosting.class);
        assertThatThrownBy(() -> EntryLine.credit(1, -5, "USD")).isInstanceOf(LedgerException.InvalidPosting.class);
        assertThatThrownBy(() -> EntryLine.credit(1, 5, "usd")).isInstanceOf(LedgerException.InvalidPosting.class);
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        assertThatThrownBy(() -> Posting.simple(TransactionKind.TRANSFER, "self", 7, 7, 100, "USD"))
                .isInstanceOf(LedgerException.InvalidPosting.class);
    }

    @Test
    void overflowingTotalsAreRejectedInsteadOfWrapping() {
        assertThatThrownBy(() -> new Posting(TransactionKind.MULTI_LEG, "huge", List.of(
                EntryLine.debit(1, Long.MAX_VALUE, "USD"), EntryLine.debit(2, Long.MAX_VALUE, "USD"),
                EntryLine.credit(3, Long.MAX_VALUE, "USD"), EntryLine.credit(4, Long.MAX_VALUE, "USD"))))
                .isInstanceOf(LedgerException.InvalidPosting.class)
                .hasMessageContaining("overflow");
    }

    @Test
    void balanceDeltaFollowsTheNormalSide() {
        EntryLine debit = EntryLine.debit(1, 300, "USD");

        assertThat(debit.balanceDelta(Side.DEBIT)).isEqualTo(300);   // an asset grows when debited
        assertThat(debit.balanceDelta(Side.CREDIT)).isEqualTo(-300); // a liability shrinks when debited
        assertThat(debit.reversed()).isEqualTo(EntryLine.credit(1, 300, "USD"));
        assertThat(AccountType.ASSET.normalSide()).isEqualTo(Side.DEBIT);
        assertThat(AccountType.LIABILITY.normalSide()).isEqualTo(Side.CREDIT);
    }
}
