package io.github.mgeladzerezo.ledger.api;

import io.github.mgeladzerezo.ledger.core.SqlStates;
import io.github.mgeladzerezo.ledger.domain.LedgerException;
import io.github.mgeladzerezo.ledger.idempotency.IdempotencyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps every failure to an RFC 9457 problem document, so clients parse one error shape. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<Problem> rejection(LedgerException e) {
        return respond(Problem.of(e));
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Problem> badRequest(BadRequestException e) {
        return respond(Problem.of(400, "bad-request", "Bad request", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> unreadable(HttpMessageNotReadableException e) {
        return respond(Problem.of(400, "bad-request", "Bad request", "the request body is not valid JSON for this operation"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(Problem.of(400, "bad-request", "Bad request", "parameter '" + e.getName() + "' has an invalid value"));
    }

    @ExceptionHandler(IdempotencyException.KeyReused.class)
    ResponseEntity<Problem> keyReused(IdempotencyException.KeyReused e) {
        return respond(Problem.of(422, "idempotency-key-reused", "Idempotency key reused", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyException.InFlight.class)
    ResponseEntity<Problem> inFlight(IdempotencyException.InFlight e) {
        return ResponseEntity.status(409)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(e.retryAfterSeconds()))
                .body(Problem.of(409, "request-in-flight", "Request in flight", e.getMessage()));
    }

    @ExceptionHandler(ChaosController.ChaosDisabledException.class)
    ResponseEntity<Problem> chaosDisabled(ChaosController.ChaosDisabledException e) {
        return respond(Problem.of(403, "chaos-disabled", "Chaos is disabled",
                "failpoints can only be armed over HTTP when ledger.chaos.enabled=true"));
    }

    /**
     * Database trouble. Lock timeouts, deadlocks and dropped connections mean the transaction was
     * rolled back and nothing was recorded, so the client is told to retry with the same key.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Problem> database(RuntimeException e) {
        if (SqlStates.isTransient(e)) {
            String state = SqlStates.of(e).orElse("unknown");
            log.warn("transient database failure (SQLSTATE {}): {}", state, e.getMessage());
            return ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(Problem.of(503, "temporarily-unavailable", "Temporarily unavailable",
                            "the request could not be completed right now and was rolled back (SQLSTATE " + state
                                    + "); retry with the same idempotency key"));
        }
        return unexpected(e);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpected(Exception e) {
        if (e instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            String detail = framework.getBody().getDetail();
            return respond(Problem.of(status, "http-" + status, framework.getBody().getTitle(),
                    detail == null ? "request rejected" : detail));
        }
        log.error("unhandled failure", e);
        return respond(Problem.of(500, "internal-error", "Internal error",
                "the request failed and was rolled back; it is safe to retry with the same idempotency key"));
    }

    private static ResponseEntity<Problem> respond(Problem problem) {
        return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
