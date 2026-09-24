# iptl-respawn-fix（重生掉线修复补丁）

## 症状

装了 Immersive Portals 6.0.7 的服务器上，玩家**死亡后点击重生**就会被踢下线：

```
[Netty Server IO #2/ERROR]: Exception caught in connection
io.netty.handler.codec.EncoderException: Failed to encode packet 'clientbound/minecraft:player_position'
Caused by: java.lang.NullPointerException: Cannot invoke "net.minecraft.resources.ResourceKey.location()" because "p_236859_" is null
    at net.minecraft.network.FriendlyByteBuf.writeResourceKey(FriendlyByteBuf.java:587)
    at net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket.handler$zcd000$immersive_portals_core$onWrite(...)
```

普通 `/tp`、传送门传送都正常，只有重生会触发。

## 原因

iPortal 给 `ClientboundPlayerPositionPacket` 加了一个额外字段 `playerDimension`，
并在写包时**无条件**调用 `FriendlyByteBuf.writeResourceKey(playerDimension)`。

这个字段只有经过 iPortal 自己 `@Overwrite` 的
`ServerGamePacketListenerImpl#teleport(double,double,double,float,float,Set)` 时才会被赋值
（字节码：`qouteall/imm_ptl/core/mixin/common/position_sync/MixinServerGamePacketListenerImpl.ip_setPlayerDimension`）。
重生（Paper/Youer 的重生流程）发出的位置包不经过这条赋值路径，字段是 `null`，
写包时抛 NPE，编码失败 → 服务端踢人（客户端显示 `Internal Exception: io.netty.handler.codec.EncoderException`）。

上游目前没有修复版本：1.21.1 NeoForge 最新就是 6.0.7（2025-06-18）。
`config/immersive_portals.json` 里也没有相关开关。

## 补丁做了什么

在**服务端**发包前（`ServerCommonPacketListenerImpl#send` 的 HEAD）检查：
如果是 `ClientboundPlayerPositionPacket`，且 iPortal 的维度字段为 `null`，
就填入玩家当前的维度 `player.level().dimension()`。

- 字段已经有值时**完全不动**，所以正常传送/传送门行为不变。
- 只对 null 的情况生效，也就是目前会崩的那种包。
- 客户端维度比较（`MixinClientPacketListener`）会因此匹配，不会触发错误的跨维度强传。
- 使用反射访问 iPortal 的 duck 接口，没装 iPortal 时该 mod 完全空转，不会崩。

## 文件

- `mods/iptl-respawn-fix-1.0.0.jar` —— 装到服务端 `mods/` 的成品（重启服务器生效）
- `iptl-fix/src/...` —— 源码
- `iptl-fix/build/classes` —— 编译产物（中间文件）

## 关于"纯服务端"声明

两处配合，效果是"客户端不需要、也不会收到"：

1. **mixin 只在服务端注入** —— `iptlfix.mixins.json` 里写在 `"server"` 段：
   ```json
   "server": ["MixinServerCommonPacketListenerImpl"]
   ```
2. **让 AutoModpack 别把它发给客户端** —— AutoModpack 的判定规则（反编译 `FileInspection.getModEnvironment` 得到）：
   - Fabric/Quilt：读 `fabric.mod.json` 的 `"environment"`；
   - Forge/NeoForge：只看 `[[dependencies.<modid>]]` 里 `modId` 为 `minecraft`/`neoforge`/`forge` 的那几条的 `side`，`side = "server"` → 服务端专用，
     在 `automodpack-server.json` 的 `autoExcludeServerSideMods: true` 下就不进客户端整合包。

   所以 `META-INF/neoforge.mods.toml` 里写的是：
   ```toml
   [[dependencies.iptl_respawn_fix]]
   modId = "neoforge"
   side = "SERVER"
   ```
   注意：NeoForge 本体的 `[[mods]]` 段没有 side 字段，`side` 只存在于依赖项里。

验证方法：看 `automodpack/host-modpack/automodpack-content.json` 的 `list` 里是否还有这个 jar（应当没有）。

## 重新编译

现在是标准的 NeoForge ModDevGradle 工程（MC/NeoForge 依赖由插件自己解析，无需手写 classpath）：

```bash
./gradlew build        # 产物: build/libs/iptl-respawn-fix-1.0.0.jar
```

改版本号时记得同时改 `build.gradle` 的 `version` 和
`src/main/resources/META-INF/neoforge.mods.toml` 里的 `[[mods]].version`。

CI：`.github/workflows/build.yml`，只在 Actions 页面手动触发（`workflow_dispatch`），
产物作为 `iptl-respawn-fix-jar` artifact 上传。

## 验证

重启后 `logs/latest.log` / `debug.log` 里应出现：

```
[mixin]: Mixing tbbk.iptlfix.mixin.MixinServerCommonPacketListenerImpl from iptlfix.mixins.json into net.minecraft.server.network.ServerCommonPacketListenerImpl
```

然后进游戏 `/kill` 一次，看是否还会被踢。

## 卸载

删掉 `mods/iptl-respawn-fix-1.0.0.jar` 即可。如果决定直接移除 iPortal，
这个补丁也应当一起删除（它只在 iPortal 存在时才有意义）。
