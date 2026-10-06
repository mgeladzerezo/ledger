package io.github.mgeladzerezo.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.mgeladzerezo.ledger.support.Api;
import io.github.mgeladzerezo.ledger.support.Api.Response;
import io.github.mgeladzerezo.ledger.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The ledger's operations end to end over HTTP: accounting effects, error contract, pagination. */
class LedgerApiTest extends IntegrationTest {

    @Test
    void depositCreditsTheCustomerAndDebitsCash() {
        long alice = api.openCustomer("USD");

        JsonNode tx = api.deposit(alice, 10_000, "USD").expect(201).json();

        assertThat(tx.get("kind").asString()).isEqualTo("DEPOSIT");
        assertThat(tx.get("entries")).hasSize(2);
        assertThat(tx.get("entries").get(0).get("side").asString()).isEqualTo("DEBIT");
        assertThat(tx.get("entries").get(1).get("side").asString()).isEqualTo("CREDIT");
        assertThat(tx.get("entries").get(1).get("accountId").asLong()).isEqualTo(alice);
        assertThat(api.balance(alice)).isEqualTo(10_000);

        long cash = tx.get("entries").get(0).get("accountId").asLong();
        JsonNode cashAccount = api.account(cash);
        assertThat(cashAccount.get("code").asString()).isEqualTo("system:cash:USD");
        assertThat(cashAccount.get("type").asString()).isEqualTo("ASSET");
        assertThat(cashAccount.get("system").asBoolean()).isTrue();
    }

    @Test
    void transferMovesMoneyAndRejectsOverdraft() {
        long alice = api.openCustomer("USD");
        long bob = api.openCustomer("USD");
        api.deposit(alice, 10_000, "USD").expect(201);

        api.transfer(alice, bob, 2_500, "USD").expect(201);
        Response rejected = api.transfer(alice, bob, 7_501, "USD");

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.header("Content-Type")).startsWith("application/problem+json");
        assertThat(rejected.problemCode()).isEqualTo("insufficient-funds");
        assertThat(rejected.json().get("type").asString()).isEqualTo("urn:ledger:problem:insufficient-funds");
        assertThat(rejected.json().get("detail").asString()).contains("7500 available, 7501 required");
        assertThat(api.balance(alice)).isEqualTo(7_500);
        assertThat(api.balance(bob)).isEqualTo(2_500);
    }

    @Test
    void withdrawalIsLimitedByTheBalance() {
        long alice = api.openCustomer("USD");
        api.deposit(alice, 1_000, "USD").expect(201);

        api.post("/api/v1/withdrawals", Map.of("accountId", alice, "amount", 600, "currency", "USD")).expect(201);
        Response second = api.post("/api/v1/withdrawals", Map.of("accountId", alice, "amount", 600, "currency", "USD"));

        assertThat(second.status()).isEqualTo(422);
        assertThat(api.balance(alice)).isEqualTo(400);
    }

    @Test
    void accountThatMayGoNegativeCanOverdraw() {
        long credit = api.openAccount("LIABILITY", "USD", false);
        long bob = api.openCustomer("USD");

        api.transfer(credit, bob, 5_000, "USD").expect(201);

        assertThat(api.balance(credit)).isEqualTo(-5_000);
    }

    @Test
    void holdReservesFundsAndCaptureMovesThem() {
        long alice = api.openCustomer("USD");
        long shop = api.openCustomer("USD");
        api.deposit(alice, 10_000, "USD").expect(201);

        JsonNode hold = api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 4_000, "currency", "USD"))
                .expect(201).json();
        String holdId = hold.get("id").asString();

        // the money is still Alice's, but she cannot spend it
        assertThat(api.balance(alice)).isEqualTo(10_000);
        assertThat(api.available(alice)).isEqualTo(6_000);
        assertThat(api.transfer(alice, shop, 6_001, "USD").problemCode()).isEqualTo("insufficient-funds");

        // partial capture: 3,000 of the 4,000 held; the remaining 1,000 is released
        JsonNode capture = api.post("/api/v1/holds/" + holdId + "/capture", Map.of("toAccountId", shop, "amount", 3_000))
                .expect(201).json();

        assertThat(capture.get("hold").get("status").asString()).isEqualTo("CAPTURED");
        assertThat(capture.get("hold").get("capturedAmount").asLong()).isEqualTo(3_000);
        assertThat(capture.get("transaction").get("kind").asString()).isEqualTo("CAPTURE");
        assertThat(api.balance(alice)).isEqualTo(7_000);
        assertThat(api.available(alice)).isEqualTo(7_000);
        assertThat(api.balance(shop)).isEqualTo(3_000);

        // a hold can be resolved once
        Response again = api.post("/api/v1/holds/" + holdId + "/capture", Map.of("toAccountId", shop));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.problemCode()).isEqualTo("hold-not-active");
        assertThat(api.post("/api/v1/holds/" + holdId + "/release", "").status()).isEqualTo(409);
    }

    @Test
    void releasedHoldGivesTheFundsBack() {
        long alice = api.openCustomer("USD");
        api.deposit(alice, 5_000, "USD").expect(201);
        String holdId = api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 5_000, "currency", "USD"))
                .expect(201).json().get("id").asString();
        assertThat(api.available(alice)).isZero();
        assertThat(api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 1, "currency", "USD")).problemCode())
                .isEqualTo("insufficient-funds");

        JsonNode released = api.post("/api/v1/holds/" + holdId + "/release", "").expect(200).json();

        assertThat(released.get("status").asString()).isEqualTo("RELEASED");
        assertThat(api.available(alice)).isEqualTo(5_000);
        assertThat(api.get("/api/v1/holds/" + holdId).expect(200).json().get("resolvedAt").isNull()).isFalse();
    }

    @Test
    void captureCannotExceedTheHold() {
        long alice = api.openCustomer("USD");
        long shop = api.openCustomer("USD");
        api.deposit(alice, 5_000, "USD").expect(201);
        String holdId = api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 1_000, "currency", "USD"))
                .expect(201).json().get("id").asString();

        Response tooMuch = api.post("/api/v1/holds/" + holdId + "/capture", Map.of("toAccountId", shop, "amount", 1_001));

        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(api.get("/api/v1/holds/" + holdId).json().get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void reversalPostsTheMirrorImageAndLinksBothWays() {
        long alice = api.openCustomer("USD");
        long bob = api.openCustomer("USD");
        api.deposit(alice, 10_000, "USD").expect(201);
        String original = api.transfer(alice, bob, 4_000, "USD").expect(201).json().get("id").asString();

        JsonNode reversal = api.post("/api/v1/transactions/" + original + "/reversals", Map.of("reason", "duplicate"))
                .expect(201).json();

        assertThat(reversal.get("kind").asString()).isEqualTo("REVERSAL");
        assertThat(reversal.get("reversesId").asString()).isEqualTo(original);
        assertThat(reversal.get("entries").get(0).get("side").asString()).isEqualTo("CREDIT");
        assertThat(reversal.get("entries").get(0).get("accountId").asLong()).isEqualTo(alice);
        assertThat(api.balance(alice)).isEqualTo(10_000);
        assertThat(api.balance(bob)).isZero();

        // the original is untouched apart from being findable through its reversal
        JsonNode reloaded = api.get("/api/v1/transactions/" + original).expect(200).json();
        assertThat(reloaded.get("reversedBy").asString()).isEqualTo(reversal.get("id").asString());
        assertThat(reloaded.get("entries")).hasSize(2);

        Response twice = api.post("/api/v1/transactions/" + original + "/reversals", Map.of("reason", "again"));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.problemCode()).isEqualTo("already-reversed");
        Response ofReversal = api.post("/api/v1/transactions/" + reversal.get("id").asString() + "/reversals", Map.of());
        assertThat(ofReversal.status()).isEqualTo(422);
    }

    @Test
    void reversalIsRejectedWhenTheRecipientAlreadySpentTheMoney() {
        long alice = api.openCustomer("USD");
        long bob = api.openCustomer("USD");
        long carol = api.openCustomer("USD");
        api.deposit(alice, 1_000, "USD").expect(201);
        String original = api.transfer(alice, bob, 1_000, "USD").expect(201).json().get("id").asString();
        api.transfer(bob, carol, 1_000, "USD").expect(201);

        Response refund = api.post("/api/v1/transactions/" + original + "/reversals", Map.of());

        assertThat(refund.problemCode()).isEqualTo("insufficient-funds");
        assertThat(api.balance(carol)).isEqualTo(1_000);
    }

    @Test
    void multiLegTransactionSplitsAPaymentWithAFee() {
        long alice = api.openCustomer("USD");
        long shop = api.openCustomer("USD");
        long fees = api.openAccount("REVENUE", "USD", false);
        api.deposit(alice, 10_000, "USD").expect(201);

        JsonNode tx = api.post("/api/v1/transactions", Map.of("description", "order 17", "entries", List.of(
                Map.of("accountId", alice, "side", "DEBIT", "amount", 1_000, "currency", "USD"),
                Map.of("accountId", shop, "side", "CREDIT", "amount", 970, "currency", "USD"),
                Map.of("accountId", fees, "side", "CREDIT", "amount", 30, "currency", "USD")))).expect(201).json();

        assertThat(tx.get("kind").asString()).isEqualTo("MULTI_LEG");
        assertThat(tx.get("entries")).hasSize(3);
        assertThat(api.balance(alice)).isEqualTo(9_000);
        assertThat(api.balance(shop)).isEqualTo(970);
        assertThat(api.balance(fees)).isEqualTo(30);
    }

    @Test
    void unbalancedMultiLegTransactionIsRejectedByTheDomainModel() {
        long alice = api.openCustomer("USD");
        long shop = api.openCustomer("USD");
        api.deposit(alice, 10_000, "USD").expect(201);

        Response response = api.post("/api/v1/transactions", Map.of("entries", List.of(
                Map.of("accountId", alice, "side", "DEBIT", "amount", 1_000, "currency", "USD"),
                Map.of("accountId", shop, "side", "CREDIT", "amount", 999, "currency", "USD"))));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.problemCode()).isEqualTo("invalid-posting");
        assertThat(api.balance(alice)).isEqualTo(10_000);
    }

    @Test
    void fxTransferBalancesEachCurrencyThroughClearingAccounts() {
        long usd = api.openCustomer("USD");
        long eur = api.openCustomer("EUR");
        api.deposit(usd, 10_000, "USD").expect(201);

        JsonNode tx = api.post("/api/v1/fx-transfers", Map.of("fromAccountId", usd, "toAccountId", eur,
                "sourceAmount", 1_000, "sourceCurrency", "USD", "targetAmount", 920, "targetCurrency", "EUR"))
                .expect(201).json();

        assertThat(tx.get("kind").asString()).isEqualTo("FX_TRANSFER");
        assertThat(tx.get("entries")).hasSize(4);
        assertThat(tx.get("metadata").get("targetAmount").asString()).isEqualTo("920");
        assertThat(api.balance(usd)).isEqualTo(9_000);
        assertThat(api.balance(eur)).isEqualTo(920);
        for (JsonNode total : api.get("/api/v1/reports/trial-balance").expect(200).json().get("totals")) {
            assertThat(total.get("balanced").asBoolean()).as("trial balance %s", total.get("currency")).isTrue();
        }
    }

    @Test
    void currencyMismatchAndUnknownAndFrozenAccountsAreRejected() {
        long usd = api.openCustomer("USD");
        long eur = api.openCustomer("EUR");
        api.deposit(usd, 1_000, "USD").expect(201);

        assertThat(api.transfer(usd, eur, 100, "USD").problemCode()).isEqualTo("currency-mismatch");
        Response unknown = api.transfer(usd, 9_999_999L, 100, "USD");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.problemCode()).isEqualTo("not-found");

        api.post("/api/v1/accounts/" + usd + "/status", Map.of("status", "FROZEN")).expect(200);
        assertThat(api.deposit(usd, 100, "USD").problemCode()).isEqualTo("account-not-open");
        api.post("/api/v1/accounts/" + usd + "/status", Map.of("status", "OPEN")).expect(200);
        api.deposit(usd, 100, "USD").expect(201);

        // closing needs an empty account, and is final
        assertThat(api.post("/api/v1/accounts/" + usd + "/status", Map.of("status", "CLOSED")).problemCode())
                .isEqualTo("account-not-empty");
        api.post("/api/v1/accounts/" + eur + "/status", Map.of("status", "CLOSED")).expect(200);
        assertThat(api.post("/api/v1/accounts/" + eur + "/status", Map.of("status", "OPEN")).problemCode())
                .isEqualTo("account-closed");
    }

    @Test
    void convenienceOperationsRefuseDebitNormalAccounts() {
        long asset = api.openAccount("ASSET", "USD", false);

        Response response = api.deposit(asset, 100, "USD");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.problemCode()).isEqualTo("invalid-request");
    }

    @Test
    void malformedRequestsGetProblemDetailsWithStatus400() {
        long alice = api.openCustomer("USD");

        Response negative = api.post("/api/v1/deposits", Map.of("accountId", alice, "amount", -5, "currency", "USD"));
        Response fractional = api.post("/api/v1/deposits", "{\"accountId\":" + alice + ",\"amount\":1.5,\"currency\":\"USD\"}");
        Response garbage = api.post("/api/v1/deposits", "{not json");
        Response badCurrency = api.post("/api/v1/deposits", Map.of("accountId", alice, "amount", 5, "currency", "usd"));
        Response duplicateCode = api.post("/api/v1/accounts", Map.of("code", api.account(alice).get("code").asString(),
                "name", "dup", "type", "LIABILITY", "currency", "USD"));

        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.problemCode()).isEqualTo("bad-request");
        assertThat(negative.header("Content-Type")).startsWith("application/problem+json");
        assertThat(fractional.status()).isEqualTo(400);
        assertThat(garbage.status()).isEqualTo(400);
        assertThat(badCurrency.status()).isEqualTo(400);
        assertThat(duplicateCode.status()).isEqualTo(409);
        assertThat(duplicateCode.problemCode()).isEqualTo("account-code-taken");
        assertThat(api.get("/api/v1/accounts/not-a-number").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/nowhere").status()).isEqualTo(404);
        assertThat(api.get("/api/v1/nowhere").header("Content-Type")).startsWith("application/problem+json");
    }

    @Test
    void apiRequiresAValidKeyButHealthAndUiDoNot() {
        Api anonymous = client(null);
        Api wrong = client("nope");

        assertThat(anonymous.get("/api/v1/accounts").status()).isEqualTo(401);
        assertThat(wrong.get("/api/v1/accounts").status()).isEqualTo(401);
        assertThat(wrong.get("/api/v1/accounts").problemCode()).isEqualTo("unauthorized");
        assertThat(anonymous.get("/actuator/health").status()).isEqualTo(200);
        JsonNode config = anonymous.get("/ui/config").expect(200).json();
        assertThat(config.get("demo").asBoolean()).isFalse();
        assertThat(config.get("apiKey").isNull()).as("the key is only handed out in demo mode").isTrue();
    }

    @Test
    void chaosEndpointRefusesToArmFailpointsWithoutTheFlag() {
        JsonNode state = api.get("/api/v1/chaos/failpoints").expect(200).json();
        assertThat(state.get("enabled").asBoolean()).isFalse();
        assertThat(state.get("failpoints")).hasSizeGreaterThanOrEqualTo(7);

        Response arm = api.post("/api/v1/chaos/failpoints/before-commit/arm", "", null);

        assertThat(arm.status()).isEqualTo(403);
        assertThat(arm.problemCode()).isEqualTo("chaos-disabled");
    }

    @Test
    void entriesArePaginatedByCursorWithoutGapsOrRepeats() {
        long alice = api.openCustomer("USD");
        for (int i = 1; i <= 7; i++) {
            api.deposit(alice, i * 100, "USD").expect(201);
        }

        List<Long> amounts = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = api.get("/api/v1/accounts/" + alice + "/entries?limit=3"
                    + (cursor == null ? "" : "&cursor=" + cursor)).expect(200).json();
            page.get("items").forEach(item -> amounts.add(item.get("entry").get("amount").asLong()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(amounts).containsExactly(700L, 600L, 500L, 400L, 300L, 200L, 100L); // newest first
        assertThat(api.get("/api/v1/accounts/" + alice + "/entries?cursor=garbage").status()).isEqualTo(400);
        assertThat(api.get("/api/v1/accounts/" + alice + "/entries?limit=0").status()).isEqualTo(400);

        JsonNode journal = api.get("/api/v1/transactions?limit=2&accountId=" + alice).expect(200).json();
        assertThat(journal.get("items")).hasSize(2);
        assertThat(journal.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void dailyStatementShowsOpeningRunningAndClosingBalances() {
        long alice = api.openCustomer("USD");
        long bob = api.openCustomer("USD");
        api.deposit(alice, 1_000, "USD").expect(201);
        api.transfer(alice, bob, 300, "USD").expect(201);

        JsonNode today = api.get("/api/v1/accounts/" + alice + "/statement").expect(200).json();
        JsonNode tomorrow = api.get("/api/v1/accounts/" + alice + "/statement?date="
                + LocalDate.now(ZoneOffset.UTC).plusDays(1)).expect(200).json();

        assertThat(today.get("openingBalance").asLong()).isZero();
        assertThat(today.get("closingBalance").asLong()).isEqualTo(700);
        assertThat(today.get("lines")).hasSize(2);
        assertThat(today.get("lines").get(0).get("balanceAfter").asLong()).isEqualTo(1_000);
        assertThat(today.get("lines").get(1).get("balanceAfter").asLong()).isEqualTo(700);
        assertThat(tomorrow.get("openingBalance").asLong()).isEqualTo(700);
        assertThat(tomorrow.get("lines")).isEmpty();
        assertThat(api.get("/api/v1/accounts/" + alice + "/statement?date=yesterday").status()).isEqualTo(400);
    }

    @Test
    void rebuildBalanceRecomputesFromEntriesAndHolds() {
        long alice = api.openCustomer("USD");
        api.deposit(alice, 5_000, "USD").expect(201);
        api.post("/api/v1/holds", Map.of("accountId", alice, "amount", 1_200, "currency", "USD")).expect(201);
        jdbc.sql("update account_balances set balance = 1, held = 0 where account_id = ?").param(alice).update();
        assertThat(api.balance(alice)).isEqualTo(1);

        JsonNode rebuilt = api.post("/api/v1/accounts/" + alice + "/rebuild-balance", "", null).expect(200).json();

        assertThat(rebuilt.get("balance").asLong()).isEqualTo(5_000);
        assertThat(rebuilt.get("held").asLong()).isEqualTo(1_200);
    }

    @Test
    void mutatingRequestWithoutIdempotencyKeyIsRejected() {
        long alice = api.openCustomer("USD");

        Response response = api.post("/api/v1/deposits", Map.of("accountId", alice, "amount", 5, "currency", "USD"), null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("detail").asString()).contains("Idempotency-Key");
        assertThat(api.post("/api/v1/deposits", Map.of("accountId", alice, "amount", 5, "currency", "USD"),
                "has space").status()).isEqualTo(400);
        assertThat(api.balance(alice)).isZero();
    }
}
