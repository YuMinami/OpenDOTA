# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

OpenDOTA (车云通讯诊断平台) is a remote vehicle diagnostics platform: a Spring Boot backend mediates between a React operator console and in-vehicle Agents over MQTT, using UDS (Unified Diagnostic Services) running over CAN/ISO-TP or DoIP. The codebase is in very early MVP — only the Spring Boot skeleton (`OpenDotaApplication` + one `HelloController`) exists; the authoritative design lives in `doc/`.

Always treat these docs as the source of truth before proposing designs:
- `doc/opendota_protocol_spec.md` — MQTT topics, message envelope, `act` types, macro system, resource arbitration, queue control
- `doc/opendota_tech_architecture.md` — tech stack, module layout, DB schema, async pipeline, MVP roadmap
- `doc/design_review.md` — gap analysis flagging what's intentionally still missing (multi-ECU scripts, task mgmt system, diagnostic-vs-task mutex, etc.)
- `sql/init_opendota.sql` — PostgreSQL DDL + mock data (ODX vehicle/ECU/service/param-codec tables + `diag_record`)

## Build & run

All Maven commands run from `opendota-server/` (the parent POM). `opendota-app` is the only executable module.

```bash
cd opendota-server

mvn clean install                          # full multi-module build
mvn spring-boot:run -pl opendota-app -am   # run the app (-am builds deps first)
mvn test -pl opendota-diag                 # tests for one module
mvn -pl opendota-mqtt test -Dtest=FooTest  # single test class
```

Runtime listens on `http://localhost:8080`; smoke endpoint is `GET /api/hello`.

> **Spring Boot version caveat** (from `opendota-server/pom.xml:7-8`): the parent is pinned to `4.0.0-SNAPSHOT`, which may not resolve from public repos. If `mvn clean install` fails to find the parent, temporarily downgrade to the latest `3.4.x` rather than hunting for snapshots — this is the documented workaround.

Java 25 with `--enable-preview` is **required** (enforced in the parent POM via `maven.compiler.enablePreview=true` and compiler `-parameters`). Virtual threads are enabled globally in `application.yml` (`spring.threads.virtual.enabled: true`) — do not disable this; it's a load-bearing selection documented in architecture §1.2.

## Module topology

Maven multi-module, all under `com.opendota.*`. Dependency graph (arrow = "depends on"):

```
opendota-app ──► opendota-diag ──► opendota-common
            └──► opendota-admin    opendota-mqtt ──► opendota-common
                                   opendota-odx  ──► opendota-common
                 opendota-diag ──► opendota-mqtt, opendota-odx
                 opendota-admin ──► opendota-common
```

- `opendota-common` — envelope DTOs (`DiagMessage<T>` record), `DiagAction` enum, hex/UDS utilities. No Spring beans.
- `opendota-mqtt` — Eclipse Paho publisher/subscriber. Owns the wire; knows nothing about ODX.
- `opendota-odx` — ODX importer, encoder (intent→hex), decoder (hex→human-readable). Pure DB-driven; no XML parsing at runtime.
- `opendota-diag` — REST controllers, SSE emitters, `DiagDispatcher`, `ChannelManager`, `TaskManager`. This is where business flows live.
- `opendota-admin` — ODX upload, vehicle/ECU CRUD.
- `opendota-app` — `@SpringBootApplication` + `application.yml`. The only module with `spring-boot-maven-plugin`.

The architecture doc (§5.1) also anticipates an `opendota-task` module for the task management system — it's not yet created, so add it as a new sibling module (update root `<modules>` + parent POM `dependencyManagement`) rather than shoving task code into `opendota-diag`.

## The one architectural pattern to internalize

The platform is **strictly async end-to-end**. Never block an HTTP thread waiting on a vehicle response. The mandated pipeline (architecture §3.1) is:

```
POST /api/cmd/... ──► build Envelope ──► MQTT publish C2V ──► return 200 {msgId}  [release thread]
                                                              │
                                   car agent processes, publishes V2C
                                                              ▼
MQTT subscriber ──► ODX decode ──► Redis PUBLISH dota:resp:{vin} ──► any Spring node's SSE emitter ──► browser
```

Consequences that catch people out:
- The node that **publishes** the command is usually **not** the node that receives the MQTT response (cluster mode) — that's why Redis Pub/Sub is the fan-out bus, not direct in-memory callbacks.
- Redis Pub/Sub does **not** persist. SSE reconnect uses `Last-Event-ID` mapped to `diag_record.id` to replay (architecture §3.2.1). If you add new V2C events, they need a persistable row or they'll be lost on SSE disconnect.
- Correlation across the whole chain is by `msgId` (UUID, set when building the envelope). Every log line in MQTT/ODX/SSE code must carry `msgId` in MDC.

## Protocol & data conventions

- **Envelope**: every MQTT message is `{msgId, timestamp, vin, act, payload}` — modeled as `DiagMessage<T>` record in `opendota-common`. Don't invent ad-hoc JSON shapes.
- **MQTT topics**: `dota/v1/{cmd|resp|event|ack}/{scenario}/{vin}` at QoS 1. Full registry in protocol spec §2.2 — consult before adding a topic.
- **`act` is an enum**, never a string literal. New `act` values go in `DiagAction` with a `@JsonValue`-bound lowercase wire name.
- **Three-layer separation** (protocol spec §1.1): cloud = business/ODX; MQTT = dumb transport; vehicle agent = UDS transport + macros only. Never push ODX semantics into the vehicle agent, and never block on millisecond-level UDS handshakes over public MQTT — time-critical loops (security access, routine polling, transfer) must be encapsulated as vehicle-side macros (`macro_security`, `macro_routine_wait`, `macro_data_transfer`).
- **DB-driven ODX**: codec rules live in `odx_param_codec.formula` / `enum_mapping` (JSONB). At runtime, look them up by `service_id`; don't parse ODX XML on the hot path.
- **REST response wrapping**: `{code, msg, data}` via `@RestControllerAdvice`. SpringDoc exposes `/swagger-ui.html`.

## Project conventions

- **Comments in Chinese** (per architecture §11.1) — this is the existing style in the docs and SQL. Follow it when adding comments to files that already use it; do not retroactively translate existing Chinese comments.
- **Prefer Java records** for DTOs/payloads — the envelope is already a record and that's the house style for immutable data.
- **No magic strings for protocol constants** — enumerate them. This applies to `act`, `status`, `macroType`, `dispatchStatus`, etc.
- **Package layout**: `com.opendota.{module}.{controller|service|config|model|...}`.
- **Commit style** (seen in `git log`): conventional commits — `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`.

## When adding features, check the gap list first

`doc/design_review.md` catalogues 12 known gaps between the current spec and the intended product (multi-ECU script orchestration, cloud task mgmt system, diag-vs-task mutex, offline task push, vehicle-side queue control, timed/conditional schedule modes, etc.) with P0/P1/P2 priorities. If a user asks for something in that list, the doc already has the intended design — read it before proposing a new one.
