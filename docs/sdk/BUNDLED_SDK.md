# Bundled SDK & Addon APIs (`LeafAnvil`)

`LeafAnvil` consumes the multi-platform LeafCraft SDK (`io.github.dailystruggle:<module>:3.3.3`) as published Maven artifacts from `LeafCraftDev/MC`, shades the runtime classes into `LeafAnvil`, and re-exports them (`api` scope) so contributors and addon developers can use every module below without adding extra runtime dependencies.

- **IDE Javadoc & Sources:** Every published `io.github.dailystruggle:<module>` artifact includes `-sources.jar`, `-javadoc.jar`, and embedded Markdown documentation under `META-INF/leaf-sdk/<module>/`.
- **Local Repository Guides:** Every SDK module's `README.md` and ADRs are projected into [`docs/sdk/`](README.md) in this repository for immediate browsing and code search.

## Bundled SDK Modules in This Repository

| Module | Maven Central Coordinate | Bundled & Resupplied | Documented Purpose | Guide |
|---|---|---|---|---|
| `server-api` | `io.github.dailystruggle:server-api:3.3.3` | Yes (`api` + shaded) | Platform-neutral scheduling (`TaskScheduler`), Folia region checks, version gates, and `ServerAccessor` capability SPIs. | [`docs/sdk/server-api/README.md`](server-api/README.md) |
| `commands-api` | `io.github.dailystruggle:commands-api:3.3.3` | Yes (`api` + shaded) | Permission-filtered command tree (`TreeCommand`), `key=value` parameters, tick-budgeted execution, and Brigadier bridge. | [`docs/sdk/commands-api/README.md`](commands-api/README.md) |
| `yaml-api` | `io.github.dailystruggle:yaml-api:3.3.3` | Yes (`api` + shaded) | Zero-dependency, block-comment-preserving YAML parser and atomic writer (`RtpYamlConfig`). | [`docs/sdk/yaml-api/README.md`](yaml-api/README.md) |
| `units-api` | `io.github.dailystruggle:units-api:3.3.3` | Yes (`api` + shaded) | Dependency-free temporal (`20t`, `1d12h`), spatial (`256c`, `2km`), and data-size (`256MB`) parsers. | [`docs/sdk/units-api/README.md`](units-api/README.md) |
| `bit-api` | `io.github.dailystruggle:bit-api:3.3.3` | Yes (`api` + shaded) | Plugin-agnostic Power-On, Continuous, and Initiated Built-In Test (`PBIT` / `CBIT` / `IBIT`) framework (`BitRunner`, `BitCodeRegistry`). | [`docs/sdk/bit-api/README.md`](bit-api/README.md) |
| `config-bit-api` | `io.github.dailystruggle:config-bit-api:3.3.3` | Yes (`api` + shaded) | Declarative YAML `@type` / `@range` / `@options` comment-directive schema validation (`DirectiveValidator`, `MessageKeyParity`). | [`docs/sdk/config-bit-api/README.md`](config-bit-api/README.md) |
| `metrics-api` | `io.github.dailystruggle:metrics-api:3.3.3` | Yes (`api` + shaded) | Platform-portable TPS, MSPT, tick-budget utilization, heap, and Folia regional telemetry SPI (`Metrics`, `MetricsSnapshot`). | [`docs/sdk/metrics-api/README.md`](metrics-api/README.md) |
| `anvil-api` | `io.github.dailystruggle:anvil-api:3.3.3` | Yes (`api` + shaded) | Off-tick read-only `.mca` / `.linear` region-file decoder (`RegionFileReader`, `AnvilReader`) and selective NBT column probes. | [`docs/sdk/anvil-api/README.md`](anvil-api/README.md) |

## Full SDK Catalog

See [`docs/sdk/README.md`](README.md) for the complete 5-layer architecture overview across all 16 SDK modules.
