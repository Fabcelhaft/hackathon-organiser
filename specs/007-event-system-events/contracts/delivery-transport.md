# Delivery Transport Contract

This project has no inbound REST API for this feature — the "contract" is what this system sends
**out** to each Destination type. Both transports carry the same JSON envelope defined in
`../data-model.md` ("Domain Event") and `event-payloads.md`.

## HTTP POST Destination

- **Method**: `POST`
- **URL**: the Destination's configured `httpUrl`, used exactly as stored (no path/query
  rewriting).
- **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Bearer <credential>` — sent only when the Destination has a non-blank stored
    `credential` (FR-019); omitted entirely otherwise.
- **Body**: the JSON envelope (see `event-payloads.md`), UTF-8 encoded.
- **Success**: any `2xx` response. The response body, if any, is ignored.
- **Failure** (eligible for retry, research.md §1/§7): any non-`2xx` response, a connection
  failure, or a timeout. After retries are exhausted, the failure is logged (FR-020b) and dropped
  — the caller that triggered the domain occurrence is never informed either way (FR-020a-1).

## Kafka Destination

- **Bootstrap servers**: the Destination's configured `kafkaBootstrapServers` (comma-separated
  `host:port` pairs, standard Kafka client format).
- **Topic**: the Destination's configured `kafkaTopic`.
- **Key**: `null` (no partitioning key — hackathon-scale volume does not need ordered-per-key
  delivery; every consumer is expected to read the whole topic).
- **Value**: the JSON envelope (see `event-payloads.md`), UTF-8 encoded, `StringSerializer`.
- **Headers**: none required. If the Destination has a non-blank stored `credential`, it is used
  as the producer's SASL `PLAIN` password (username: the Destination's `name`) — the minimal
  authenticated setup a self-hosted or managed Kafka cluster typically expects; an unauthenticated
  broker simply ignores an unset credential.
- **Success**: broker acknowledges the produced record (default acks per the client's own
  defaults).
- **Failure** (eligible for retry): any producer-reported send exception (broker unreachable,
  unknown topic, auth failure, etc.). After retries are exhausted, the failure is logged (FR-020b)
  and dropped, identically to the HTTP path.

## Retry policy (both transports)

`Retry.backoff(3, Duration.ofSeconds(2))` (research.md §7) — 3 retries beyond the initial attempt,
exponential backoff starting at 2 seconds, Reactor's default jitter. Identical for both transports
so `EventPublisher` applies it once around whichever `Mono<Void>` the transport-specific sender
returns, rather than each sender re-implementing it.
