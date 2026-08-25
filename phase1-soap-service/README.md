# Phase 1 — Contract-First SOAP Inventory Service

A SOAP web service over the `inventory_mgmt` PostgreSQL database, built
**contract-first**: the XSD is written by hand, and both the Java classes and the
WSDL are generated from it.

- **Stack:** Java 21, Spring Boot 4.1.1, Spring-WS 5.0, JAXB, JDBC
- **Port:** 8081
- **Contract:** [`src/main/resources/xsd/inventory-v1.xsd`](src/main/resources/xsd/inventory-v1.xsd)

## Prerequisites

1. **PostgreSQL running** with the `inventory_mgmt` database from the SQL project
   (schema `01` through views `04` and triggers `05` all applied).
   Start Postgres.app and check its menu-bar icon says *Running*.
2. **Java 21.** Already installed. Maven is **not** required — the project ships
   with the Maven wrapper (`./mvnw`).

## Run it

```bash
cd phase1-soap-service && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

The first build downloads Maven and dependencies (a few minutes); later runs are seconds.

## See the generated contract

```bash
curl -s http://localhost:8081/ws/inventory.wsdl | xmllint --format - | head -60
```

Nobody wrote that WSDL. Spring-WS built it from the XSD at startup — including
the SKU regex and the movement-type enumeration, so consumers receive your
validation rules as part of the contract.

## Call the operations

```bash
curl -s -X POST http://localhost:8081/ws -H "Content-Type: text/xml;charset=UTF-8" -H 'SOAPAction: ""' --data-binary @samples/01-get-product.xml | xmllint --format -
```

Swap in any file from `samples/`:

| File | What it shows |
|---|---|
| `01-get-product.xml` | Simple read by key |
| `02-get-stock-level.xml` | Optional element — omit `warehouseCode` for all warehouses |
| `03-list-low-stock.xml` | Reorder report, served off the `v_low_stock_items` view |
| `04-record-movement.xml` | **Write path** — compare `quantityBefore` / `quantityAfter` |
| `05-invalid-sku-FAILS.xml` | Schema validation rejects it before any Java runs |
| `06-not-found-FAULT.xml` | Structured business fault with `<inv:InventoryError>` detail |

## The two things worth understanding

**1. Validation you never wrote.** Send `05-invalid-sku-FAILS.xml` and you get:

```
cvc-pattern-valid: Value 'not-a-valid-sku' is not facet-valid with respect to
pattern '[A-Z]{3,4}-[A-Z0-9]{3,5}-?[0-9]{0,5}' for type 'SkuType'.
```

There is no `if (!sku.matches(...))` anywhere in this codebase. The
`PayloadValidatingInterceptor` enforces the XSD at the boundary. Constraints
declared once in the schema are enforced everywhere, for free, and published to
consumers in the WSDL.

**2. The service records events; the database derives consequences.** Send
`04-record-movement.xml` and the response shows the quantity rising by 20 — but
`InventoryRepository.insertMovement()` only ever inserts into `stock_movements`.
The `trg_stock_movement_apply` trigger you wrote updates `stock_levels`. Same
separation you proved in the SQL project, now visible through a service contract.

## Regenerating after an XSD change

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean compile
```

Generated sources land in `target/generated-sources/jaxb` and are **never**
hand-edited or committed. Change the XSD; the Java follows.

## Exercises

See the Phase 1 section of [`../ROADMAP.md`](../ROADMAP.md) — versioning the
namespace, writing a contract test, and calling the service with
`WebServiceTemplate`.

## Gotchas already handled here

- **`WsConfigurerAdapter` no longer exists** in Spring-WS 5 — implement the
  `WsConfigurer` interface instead (it has default methods).
- **A top-level XSD element ending in `Fault`** makes Spring-WS invent a bogus
  WSDL operation, because `faultSuffix` defaults to `"Fault"`. The error element
  here is named `InventoryError` for exactly that reason.
- **`--` is illegal inside an XML comment.** It will bite you when pasting shell
  commands into sample files.
