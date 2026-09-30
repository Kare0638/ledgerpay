package com.ledgerpay.payment.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a write at most once per (merchant, scope, key) — the request layer of design §8.
 *
 * <p>The key row and the business writes share <b>one transaction</b>:
 *
 * <ol>
 *   <li>{@code INSERT ... ON CONFLICT DO NOTHING} claims the key. A concurrent request with the
 *       same key blocks on the primary key until the first one commits or rolls back; only the
 *       database decides the winner, never a "check, then insert".
 *   <li>If the key was already there: a different request hash is 422; a completed request is
 *       replayed; an in-progress one is 409.
 *   <li>Otherwise the action runs and the key is marked COMPLETED with its response.
 * </ol>
 *
 * If the action throws, everything rolls back, including the key, which can then be reused.
 */
@Component
public class IdempotentExecutor {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public IdempotentExecutor(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** What the action produced: an HTTP status and a body to return now and on every replay. */
  public record Result(int status, Object body) {}

  @Transactional
  public IdempotentResponse execute(
      String merchantId, String scope, String key, Object request, Supplier<Result> action) {
    String requestHash = RequestHash.of(request);
    boolean claimed =
        jdbc.sql(
                    """
                INSERT INTO idempotency_keys (merchant_id, scope, idem_key, request_hash, status)
                VALUES (:merchant, :scope, :key, :hash, 'IN_PROGRESS')
                ON CONFLICT (merchant_id, scope, idem_key) DO NOTHING""")
                .param("merchant", merchantId)
                .param("scope", scope)
                .param("key", key)
                .param("hash", requestHash)
                .update()
            == 1;
    if (!claimed) {
      return replay(merchantId, scope, key, requestHash);
    }

    Result result = action.get();
    JsonNode body = json.valueToTree(result.body());
    jdbc.sql(
            """
            UPDATE idempotency_keys
            SET status = 'COMPLETED', response_code = :code, response_body = CAST(:body AS jsonb)
            WHERE merchant_id = :merchant AND scope = :scope AND idem_key = :key""")
        .param("code", result.status())
        .param("body", body.toString())
        .param("merchant", merchantId)
        .param("scope", scope)
        .param("key", key)
        .update();
    return new IdempotentResponse(result.status(), body, false);
  }

  private IdempotentResponse replay(
      String merchantId, String scope, String key, String requestHash) {
    Optional<StoredKey> stored =
        jdbc.sql(
                """
                SELECT request_hash, status, response_code, response_body::text AS body
                FROM idempotency_keys
                WHERE merchant_id = :merchant AND scope = :scope AND idem_key = :key""")
            .param("merchant", merchantId)
            .param("scope", scope)
            .param("key", key)
            .query(
                (rs, row) ->
                    new StoredKey(
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        rs.getInt("response_code"),
                        rs.getString("body")))
            .optional();
    if (stored.isEmpty()) {
      throw new IdempotencyInProgressException();
    }
    if (!stored.get().requestHash().equals(requestHash)) {
      throw new IdempotencyKeyReusedException();
    }
    if (!"COMPLETED".equals(stored.get().status())) {
      throw new IdempotencyInProgressException();
    }
    return new IdempotentResponse(stored.get().responseCode(), parse(stored.get().body()), true);
  }

  private JsonNode parse(String body) {
    try {
      return json.readTree(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored idempotent response is not JSON", e);
    }
  }

  private record StoredKey(String requestHash, String status, int responseCode, String body) {}
}
