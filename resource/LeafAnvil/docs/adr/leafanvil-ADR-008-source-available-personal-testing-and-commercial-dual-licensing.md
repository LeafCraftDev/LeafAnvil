# leafanvil-ADR-008: Public Source-Available Dual Licensing (`PolyForm-Noncommercial-1.0.0` for Personal & Private Testing + `LicenseRef-LeafCraft-Commercial`)

- **Status:** Accepted
- **Date:** 2026-11-18
- **Amends:** [`leafanvil-ADR-001`](leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md) Section 5
- **Extends:** [`ADR-108`](../../../../docs/adr/ADR-108-all-mit-licensing-pro-as-support.md), [`ADR-126`](../../../../docs/adr/ADR-126-multi-product-monorepo-release-architecture.md), [`leafanvil-ADR-003`](leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md), [`leafanvil-ADR-004`](leafanvil-ADR-004-pumpkinmc-rust-lca-direct-section-bridge-and-golden-fixtures.md)

## Context

Under [`leafanvil-ADR-001`](leafanvil-ADR-001-Multi-Loader-lca-world-storage-and-bytecode-interceptor.md) Section 5, the `.lca` specification (`LCA-ICD-1.0`), `api/anvil-api`, and `LeafAnvil-Net` (`:resource:LeafAnvil:leafanvil-net`) were published under the `MIT` License, while the `LeafAnvil` world-storage carrier (`resource/LeafAnvil/leafanvil-*`) remained closed-source in `LeafCraftDev/MC` under `LicenseRef-LeafCraft-Commercial` (`ANVIL_PUBLIC_RESOURCE_PATHS` exported only documentation to `LeafCraftDev/LeafAnvil`).

Keeping the runtime storage source private prevented developers, researchers, and hobbyist server owners from inspecting `NmsRegionStorageInterceptor`, reproducing the 8-platform benchmark suite from source, auditing bytecode/safety hooks, or building `LeafAnvil` locally for singleplayer worlds, private LAN/friends servers, and CI testing without first purchasing a commercial server license. Conversely, relicensing the entire `LeafAnvil` server storage runtime to `MIT` would forfeit commercial licensing on monetized servers and commercial hosting networks.

## Decision

1. **Dual-License `resource/LeafAnvil` (`PolyForm-Noncommercial-1.0.0 OR LicenseRef-LeafCraft-Commercial`):**
   - **Track 1 — Personal Use, Research & Private Testing (`PolyForm-Noncommercial-1.0.0`):** `resource/LeafAnvil/LICENSE` (and the `license` metadata in `leafanvil-fabric` `fabric.mod.json` and `leafanvil-neoforge` `neoforge.mods.toml`) includes the full verbatim **PolyForm Noncommercial License 1.0.0**, granting free source inspection, modification, compilation, and execution for personal singleplayer, non-commercial private servers, private testing, benchmarking, security auditing, academic research, and noncommercial organizations.
   - **Track 2 — Commercial Server & Network Runtime (`LicenseRef-LeafCraft-Commercial`):** Operating `LeafAnvil` on commercially monetized Minecraft servers, proxy networks, or hosted game services remains governed by the **LeafCraft Commercial Software License** (`Part II` of `resource/LeafAnvil/LICENSE`), fulfilled via BuiltByBit or a direct LeafCraft license agreement.
   - **Track 3 — Permanent `MIT` Interoperability Carve-Out (`LICENSE-MIT`):** World save archives (`.lca`, `.mca`, `.linear`, `.bak`) remain 100% user-owned, and `LCA-ICD-1.0` (`api/anvil-api/docs/ICD.md`), `api/anvil-api`, `LeafAnvil-Net` (`:resource:LeafAnvil:leafanvil-net`, `LeafAnvil-Net-<version>.jar`), and `resource/LeafAnvil/rust/lca-format` remain 100% licensed under the `MIT` License (with `platforms/neoforge/` carrier glue remaining `GPL-3.0-or-later`). `LeafAnvilNetJarPackagingTest` enforces at build time that `LeafAnvil-Net` carries `MIT` metadata (`LICENSE-MIT`) and zero `PolyForm-Noncommercial-1.0.0` or `LicenseRef-LeafCraft-Commercial` descriptors or disk-storage classes.

2. **Full Public Source Projection to `LeafCraftDev/LeafAnvil` (`sync_public_oss_pr.py --target anvil`):**
   - `scripts/pre_push_guard.py` and `scripts/release/sync_public_oss_pr.py` export the complete `resource/LeafAnvil/**` tree (`leafanvil-common`, `leafanvil-paper`, `leafanvil-fabric`, `leafanvil-neoforge`, `leafanvil-plugin`, `leafanvil-net`, `rust/lca-format`, and `.github/workflows/release-anvil.yml`) to `LeafCraftDev/LeafAnvil` (`--target anvil`), while keeping `resource/LeafAnvil/**` excluded from sibling public projections (`rtp`, `sleep`, `treegen`, `sleuth`, `fuzz`, `video`, `corpus-atlas`).
   - Exported `LeafCraftDev/LeafAnvil` places `resource/LeafAnvil/LICENSE` (`PolyForm-Noncommercial-1.0.0 OR LicenseRef-LeafCraft-Commercial`) at root `LICENSE` alongside root `LICENSE-MIT` (required by `:resource:LeafAnvil:leafanvil-net:processResources`), preserves `:paper:paper-common` (`PaperLcaRegionFileHook`) in `sanitize_settings_gradle`, and guards `platforms/paper/paper-common/build.gradle` with `hasRtpCore` so `./gradlew :resource:LeafAnvil:build` succeeds standalone without `:rtp-core`.

3. **Release Binary Separation (`.github/workflows/release-anvil.yml`):**
   - Public GitHub Releases (`leafanvil-v<semver>`) continue attaching only the `MIT`-licensed `LeafAnvil-Net-<version>.jar` companion binary (with `.sha256`, `.sha512`, and `.asc`), while pre-built `LeafAnvil-<version>.jar` commercial server binaries continue publishing to BuiltByBit. Personal and private-testing users build `LeafAnvil-<version>.jar` directly from the public `LeafCraftDev/LeafAnvil` source repository (`./gradlew :resource:LeafAnvil:build`).

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Keep `resource/LeafAnvil` closed-source in `LeafCraftDev/MC` | Blocks personal singleplayer/private-testing builds from source, third-party security audits of `NmsRegionStorageInterceptor`, and reproducible community benchmark verification. |
| Relicense all of `resource/LeafAnvil` under `MIT` | Grants unrestricted commercial server hosting and commercial binary resale without a commercial license. |
| Attach pre-built `LeafAnvil-<version>.jar` to public GitHub Releases | Blurs the fulfillment boundary with BuiltByBit commercial server downloads; compiling from source via `./gradlew :resource:LeafAnvil:build` cleanly serves developers, testers, and personal users. |

## Consequences

- **Positive:**
  - Developers, security auditors, benchmarkers, and hobbyist players can clone `LeafCraftDev/LeafAnvil`, inspect every line of `NmsRegionStorageInterceptor`, `LeafAnvilWorldConverter`, and `LcaInMemoryChunkCoordinator`, and build `LeafAnvil-<version>.jar` locally for personal use and private testing under `PolyForm-Noncommercial-1.0.0`.
  - Commercial/monetized servers retain a clear commercial licensing path (`LicenseRef-LeafCraft-Commercial` via BuiltByBit), while `.lca`, `anvil-api`, `LeafAnvil-Net`, and `lca-format` remain 100% `MIT` with zero format lock-in.
- **Negative / Trade-offs:**
  - Because `PolyForm-Noncommercial-1.0.0` restricts commercial use, documentation and storefront copy must describe `LeafAnvil` accurately as source-available / dual-licensed (free for personal use & private testing; commercial license for monetized servers) rather than claiming permissive `MIT` licensing on the disk-storage carrier.
