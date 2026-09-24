# Immersive Portals Respawn Fix (`iptl_respawn_fix`)

A tiny **server-side** NeoForge 1.21.1 compatibility patch that stops players from being
kicked when they **respawn** on servers running **Immersive Portals 6.0.7**.

```
Internal Exception: io.netty.handler.codec.EncoderException:
    Failed to encode packet 'clientbound/minecraft:player_position'
Caused by: java.lang.NullPointerException:
    Cannot invoke "net.minecraft.resources.ResourceKey.location()" because "p_236859_" is null
    at net.minecraft.network.FriendlyByteBuf.writeResourceKey(FriendlyByteBuf.java:587)
    at net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.handler$zcd000$immersive_portals_core$onWrite(...)
```

Death → click respawn → disconnect. Normal `/tp`, portals and joining are unaffected.

---

## Root cause

Immersive Portals adds an extra `playerDimension` field to
`ClientboundPlayerPositionPacket` and writes it **unconditionally** in the packet's
`write` hook (`qouteall.imm_ptl.core.mixin.common.position_sync.MixinPlayerPositionLookS2CPacket#onWrite`):

```java
private void onWrite(FriendlyByteBuf buf, CallbackInfo ci) {
    buf.writeResourceKey(this.playerDimension);   // NPE when null
}
```

On the server side that field is populated in exactly **one** place: Immersive Portals'
`@Overwrite` of
`ServerGamePacketListenerImpl#teleport(double, double, double, float, float, Set)`.

Verified by disassembling `immersive_portals-6.0.7-all.jar`
(`javap -c -p qouteall/imm_ptl/core/mixin/common/position_sync/MixinServerGamePacketListenerImpl.class`):

```
336: invokespecial ClientboundPlayerPositionPacket."<init>":(DDDFFLjava/util/Set;I)V
...
350: invokevirtual Level.dimension:()Lnet/minecraft/resources/ResourceKey;
356: invokeinterface IEPlayerPositionLookS2CPacket.ip_setPlayerDimension:(Lnet/minecraft/resources/ResourceKey;)V
370: invokevirtual ServerGamePacketListenerImpl.send:(Lnet/minecraft/network/protocol/Packet;)V
```

Any position packet produced by **another** code path therefore leaves `playerDimension`
`null`. The respawn flow is such a path (notably on Paper-derived hybrid servers such as
Youer/Mohist where `PlayerList#respawn` drives the teleport itself), so the very first
position packet after a respawn throws inside the encoder and the player is disconnected.

The upstream 1.21.1 NeoForge build is 6.0.7 (2025-06-18) — currently the newest release —
so there is no fixed version to update to.

## The fix

Hook the **last common point** of every outgoing packet and fill in the missing dimension:

```java
@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinServerCommonPacketListenerImpl {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"))
    private void iptlfix$fillMissingPositionDimension(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        // if packet is ClientboundPlayerPositionPacket && IP's dimension field is null
        //    -> set it to player.level().dimension()
    }
}
```

Why this shape:

* `send(Packet, PacketSendListener)` is the single funnel of
  `ServerCommonPacketListenerImpl` (the 1-arg overload delegates to it), so it covers
  **every** creation path — including paths added by other mods or server forks.
* The value written is the player's **current** dimension. For a respawn the client has
  already been switched to that dimension by the preceding `ClientboundRespawnPacket`
  (sent earlier in the same `PlayerList#respawn` sequence), so the client's own
  dimension comparison matches and no bogus cross-dimension teleport is triggered.
* Packets that already carry a dimension are **left untouched** — portals and normal
  teleports behave exactly as before.
* Immersive Portals is accessed **reflectively** through its duck interface, so the mod
  does nothing (and does not crash) when Immersive Portals is not installed.

Alternatives that were considered and rejected:

| Approach | Why not |
| --- | --- |
| Patch IP's `MixinPlayerPositionLookS2CPacket` to skip nulls | Breaks the wire format: the client still reads the field, causing buffer desync |
| Patch the write side *and* the client read side | Requires shipping a modified mod jar to every client and changes IP's protocol |
| Write a hardcoded fallback dimension | Wrong for respawns outside the overworld (the client would force-teleport to the wrong dimension) |
| Remove Immersive Portals | Works, but loses the feature the pack is built around |

## Requirements

* Minecraft 1.21.1, NeoForge 21.1.x (tested: NeoForge 21.1.250 on a Youer 1.21.1 Paper-hybrid server)
* Immersive Portals 6.0.7 for NeoForge (optional at runtime — the patch is inert without it)

## Install

1. Drop `iptl-respawn-fix-1.0.0.jar` into the **server's** `mods/` folder.
2. Restart the server.
3. Confirm in the log:

   ```
   [mixin]: Mixing MixinServerCommonPacketListenerImpl from iptlfix.mixins.json into net.minecraft.server.network.ServerCommonPacketListenerImpl
   ```

No client-side installation is needed. The mixin is declared in the `server` section of
`iptlfix.mixins.json`, and the mod contains no `@Mod` entrypoint, no network channels and
no client code.

### Keeping it out of an AutoModpack client pack

AutoModpack's `autoExcludeServerSideMods` reads the `side` of the
`[[dependencies.<modid>]]` entry whose `modId` is `minecraft` / `neoforge` / `forge`
(Forge/NeoForge) or `fabric.mod.json`'s `"environment"` (Fabric/Quilt). This mod therefore
declares:

```toml
[[dependencies.iptl_respawn_fix]]
modId = "neoforge"
type = "required"
versionRange = "[21.1.0,)"
ordering = "NONE"
side = "SERVER"
```

With `autoExcludeServerSideMods: true`, the jar is then excluded from
`automodpack/host-modpack/automodpack-content.json` and never sent to clients.

## Build

Standard NeoForge [ModDevGradle](https://github.com/neoforged/ModDevGradle) project — no
hand-written classpath, no vendored jars: ModDevGradle resolves Minecraft 1.21.1 and
NeoForge 21.1.250 itself.

```bash
./gradlew build          # -> build/libs/iptl-respawn-fix-1.0.0.jar
```

* JDK 21 and the Gradle wrapper are all that is required.
* Version bumps: keep `version` in `build.gradle` and `[[mods]].version` in
  `src/main/resources/META-INF/neoforge.mods.toml` in sync.
* The Mixin annotation processor is intentionally disabled (`-proc:none`): the runtime is
  Mojang-mapped and needs no refmap, and the processor would only drag ASM onto the
  processor path.

### CI

`.github/workflows/build.yml` builds the jar on demand (**Actions → Build → Run
workflow**, `workflow_dispatch` only — it does not run on push). It runs
`./gradlew build`, checks the packaged jar for the expected entries, and then uploads the
jar as a **non-archived artifact** (`actions/upload-artifact@v7` with `archive: false`),
so downloading the artifact hands you the plain `.jar` rather than a `.zip`
([GitHub changelog](https://github.blog/changelog/2026-02-26-github-actions-now-supports-uploading-and-downloading-non-zipped-artifacts/)).

## Uninstall

Delete the jar and restart. If you later decide to remove Immersive Portals instead,
remove this mod as well.

## Verification

```
/kill            # die
# click respawn
```

Before the patch the server logs `Failed to encode packet ... player_position` and the
player is disconnected; after the patch no such line appears.

## References

* Immersive Portals for NeoForge — <https://github.com/iPortalTeam/ImmersivePortalsModForNeo>
* Immersive Portals (Neo)Forge on Modrinth — <https://modrinth.com/mod/immersive-portals-neoforge>
* Background on Paper sending position packets itself —
  <https://github.com/PaperMC/Paper/commit/2cab696>
* AutoModpack `autoExcludeServerSideMods` behaviour derived from
  `pl.skidam.automodpack_core.utils.FileInspection#getModEnvironment` in
  `automodpack-mc1.21.1-neoforge-4.0.6.jar`

This is an independent compatibility patch; it is not affiliated with the Immersive
Portals team. The analysis above comes from disassembling the published
`immersive_portals-6.0.7-all.jar`.

## Credits and third-party notices

* This patch contains **no code, class files or assets from Immersive Portals**. It
  interoperates with it only by *name*: the duck interface
  `qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket` and its two methods
  `ip_getPlayerDimension` / `ip_setPlayerDimension` are looked up reflectively at runtime.
* Immersive Portals is licensed under the **Apache License 2.0**
  (Copyright 2020 qouteall — <https://github.com/iPortalTeam/ImmersivePortalsMod>); the
  NeoForge port used for testing declares `license = "Apache-2.0"` in its
  `META-INF/neoforge.mods.toml`. Apache-2.0 §1 explicitly excludes works that "merely link
  (or bind by name) to the interfaces of" the Work from being Derivative Works, and no
  Immersive Portals artifact is redistributed here, so this project does not have to be
  licensed under Apache-2.0. The notice is included for clarity only.
* Minecraft / NeoForge are not redistributed; they are used as compile-time dependencies
  against an existing server installation.
* AutoModpack's server-side-mod detection (the `side = "SERVER"` trick) was identified by
  decompiling `automodpack-mc1.21.1-neoforge-4.0.6.jar` for interoperability; no
  AutoModpack code is used.
* Not affiliated with, or endorsed by, the Immersive Portals team. "Immersive Portals" is
  used descriptively to state what this patch is compatible with.

## License

MIT — see [LICENSE](LICENSE).
