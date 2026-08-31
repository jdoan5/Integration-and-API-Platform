# Phase 5 — MCP server & LangGraph agent

The inventory platform's **fourth contract**, this one written for language
models: an **MCP server** exposing the system as tools, and a **LangGraph agent**
on **Azure OpenAI** that consumes it.

- **Server:** [`inventory_mcp/`](inventory_mcp/) — stdio and streamable HTTP on `:8084`
- **Agent:** [`agent/`](agent/) — LangGraph + `langchain-mcp-adapters`, provider-pluggable
- **Contract:** the MCP tool schemas, published from the same OpenAPI spec as Phase 2

## Run it

No credentials, no gateway, no database required:

```bash
python3.14 -m venv .venv && ./.venv/bin/pip install -e ".[agent,dev]"
```

```bash
./verify.sh
```

Ask it something (spawns the server over stdio automatically):

```bash
./.venv/bin/python -m agent.cli "what needs restocking?"
```

With no API key the agent falls back to a **scripted offline model** that still
drives the real MCP tools against the real gateway. What you lose without
credentials is the language, not the plumbing.

For live data, start the stack first:

```bash
docker compose up -d redis kong
```

```bash
cd ../phase2-rest-facade && ./mvnw spring-boot:run
```

## Attach it to Claude Code

The most direct demo — no agent code involved, just an MCP client and the server:

```bash
claude mcp add inventory -- $(pwd)/.venv/bin/python -m inventory_mcp
```

Then ask Claude Code *"which SKUs are below their reorder point?"* and it calls
the same six tools. See [`claude-mcp-config.example.json`](claude-mcp-config.example.json)
for the Claude Desktop form.

## Why this is a contract phase, not an AI phase

Every phase of this project has published a contract. This one publishes a
fourth, and the interesting part is who reads it:

| Phase | Contract | Consumer | What stops a bad call |
|---|---|---|---|
| 1 | XSD / WSDL | A program someone wrote | Schema validation at the boundary |
| 2 | OpenAPI | A developer | Bean Validation, HTTP status codes |
| 4 | Avro | A stream | The registry rejects the *schema change* |
| 5 | **MCP tool schemas** | **A model, improvising** | Everything above, all at once |

The first three consumers are deterministic. Someone read the contract, wrote
code against it, and tested it. Phase 5's consumer reads the contract *at
runtime* and decides what to do next based on prose. It will retry, invent an
argument that looks plausible, and call the write endpoint twice.

So the guards in this repo — the XSD's closed enumerations, the facade's
`Idempotency-Key`, Kong's `key-auth` and rate limiting — stop being defensive
programming and start being load-bearing. **They were all built for
human-written clients, and none of them needed changing to hold a model.** That
is the finding worth reporting, and it is why this is Phase 5 of an integration
project rather than a separate AI repo.

## The tools

Six tools, five reads and one write, all going through Kong rather than direct
to `:8082` — the agent gets the same auth, rate limiting and correlation ID as
every other consumer.

| Tool | Annotation | Calls |
|---|---|---|
| `get_product` | `readOnlyHint` | `GET /api/v1/products/{sku}` |
| `get_stock` | `readOnlyHint` | `GET /api/v1/stock/{sku}` |
| `list_low_stock` | `readOnlyHint` | `GET /api/v1/low-stock` |
| `daily_movement_totals` | `readOnlyHint` | `GET :8083/events/daily-totals` |
| `platform_status` | `readOnlyHint` | cache stats + event pipeline health |
| `record_movement` | **`destructiveHint`** | `POST /api/v1/movements` |

Plus three **resources** and two **prompts**, which most MCP demos skip:

- `inventory://contract/openapi` serves the Phase 2 spec itself — the same
  document governs the REST consumer and the LLM consumer
- `inventory://contract/vocabulary` — the SKU pattern, warehouse pattern and
  movement enum, so a model can check itself before calling
- `inventory://gateway/policy` — what the platform enforces regardless of intent
- `investigate_low_stock(sku)` and `restock_plan(warehouse)` package the
  investigation so the answer does not depend on how the question was phrased

## The idempotency key is the whole trick

An LLM calling a write endpoint twice is not an edge case, it is Tuesday. The
naive fix is a random key per call, which is worse than none: every retry
becomes a *new* movement and stock silently double-counts.

So the key is derived from the **intent**, not the attempt:

```python
uuid5(NAMESPACE, f"{session_id}:{sku}:{warehouse}:{type}:{quantity}")
```

Two identical calls in one session collapse to one movement and the second
response carries `replayed: true`. The same movement tomorrow gets a different
session id and correctly records again. The model cannot opt out — it never
sees the key.

This is why `record_movement` is annotated `destructiveHint: true` **and**
`idempotentHint: true`, which reads like a contradiction until you notice the
idempotency is a property of the server, not of the operation.

## Approval, and a budget

`record_movement` is wrapped in `HumanInTheLoopMiddleware`, so the graph
**pauses inside the tool call**, checkpoints, and waits:

```
========================================================================
The agent wants to WRITE to the inventory database.

Tool: record_movement
{ "movement_type": "OUT", "quantity": 25, "sku": "ELEC-LAP-001", ... }
========================================================================
Approve this write? [y/N]
```

Rejecting returns a normal tool result, so the model explains itself to the
user instead of crashing. Only the write is gated — interrupting before every
read would make the agent unusable.

There is also a `ToolCallLimitMiddleware` capped at **15 calls**, deliberately
below Kong's **20/minute**. A model that decides to check forty SKUs one at a
time would otherwise hit the gateway's 429 halfway through an answer; the cap
turns an infrastructure failure into a predictable agent behaviour.

## Gotchas found building this

- **FastMCP defaults to port 8000 — which is Kong's proxy port in this repo.**
  The server bound on top of the gateway and every tool call 404'd against
  Kong's router. Moved to `:8084`, which is also the only free port left
  between the SOAP service and the Schema Registry.
- **stdout belongs to the protocol.** On stdio transport a stray `print()`
  corrupts the JSON-RPC stream and the client reports a parse error that names
  no cause. All logging goes to stderr, and the server's default INFO level was
  turned down to WARNING because it logged every `ListToolsRequest`.
- **Tool results are typed content blocks, not strings.** Printing
  `message.content` directly leaks `{'type': 'text', 'id': 'lc_...'}` into the
  answer. Both the CLI trace and the offline model flatten them.
- **`create_react_agent` is deprecated.** LangGraph 1.0 moved it to
  `langchain.agents.create_agent`, and the parameter renamed from `prompt` to
  `system_prompt`. Tutorial code written a year ago compiles and warns.
- **A warehouse that does not exist returns `200 []`, not `404`.** Only found by
  running against real data. An empty array is indistinguishable from "stocked
  here, quantity zero" — a genuine zero row *does* come back populated — so
  passing it through made the agent report *"no stock at WH-NYC"* about a
  warehouse that isn't real. A silent wrong answer is worse than an error, so
  `get_stock` raises one naming both possibilities. A malformed SKU on that same
  path returns `502` and an unknown SKU returns `404`: three different failures,
  three different shapes, on one endpoint.
- **`mcp` 2.x exists, and this pins to 1.x on purpose.** The v2 SDK renamed
  `FastMCP` to `MCPServer` and *removed* `mcp.server.fastmcp`, so 2.x is an
  import error here — and `langchain-mcp-adapters` pins `mcp<2` regardless. The
  bound is in `pyproject.toml` rather than left to the adapter, because
  installing the server without the `agent` extra would otherwise pull 2.x and
  fail on the first import.
- **`WH-EAST` is not a warehouse.** The XSD pattern `WH-[A-Z]{2,4}` admits far
  more codes than the database seeds, so a model reading only the regex invents
  plausible ones and gets a 404 it cannot diagnose. The vocabulary resource now
  publishes the actual list — `WH-WEST`, `WH-CENT`, `WH-EAST` — because a
  pattern is not a value set.
- **A malformed SKU on `/stock/{sku}` returns 502, not 400.** That path has no
  server-side validation, so the XSD rejects it upstream and the fault surfaces
  as a gateway error — which reads to a model like an outage rather than its own
  mistake. The tools validate with `re.fullmatch` before calling; `re.match`
  would wrongly accept `ELEC-LAP-001-JUNK`.
- **Azure deployment name ≠ model name.** `AZURE_OPENAI_DEPLOYMENT` is whatever
  you called the deployment in the portal; the model behind it is a property of
  that deployment. Passing `gpt-4o` when the deployment is called `prod-chat`
  fails with a 404 that mentions neither.

## Configuration

Every value has a working local default, so none of this is required:

| Variable | Default | Purpose |
|---|---|---|
| `MCP_BASE_URL` | `http://localhost:8000` | Gateway, not the facade |
| `MCP_API_KEY` | `local-demo-key-internal` | Kong consumer key |
| `MCP_EVENTS_URL` | `http://localhost:8083` | Phase 4 status app |
| `MCP_PORT` | `8084` | Streamable HTTP port |
| `MCP_LLM_PROVIDER` | `auto` | `azure` / `openai` / `anthropic` / `offline` |
| `AZURE_OPENAI_ENDPOINT` | — | e.g. `https://<resource>.openai.azure.com/` |
| `AZURE_OPENAI_API_KEY` | — | |
| `AZURE_OPENAI_DEPLOYMENT` | `gpt-4o` | The *deployment* name, not the model |
| `OPENAI_API_VERSION` | `2024-10-21` | Azure API version |

`auto` picks whichever provider actually has credentials and falls back to
`offline`, so the same command works on a bare laptop and in a deployment.

## Tests

```bash
./.venv/bin/python -m pytest tests/ -q
```

32 tests, no network: `respx` mocks the HTTP layer and the MCP SDK's in-memory
transport carries real client/server sessions. They cover idempotency-key
derivation, closed-set validation before the write leaves the process, error
translation written for a model to act on, the approval interrupt, and the call
budget staying under the gateway's rate limit.
