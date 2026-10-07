# Dregora game adapter — first implementation

此目录提供独立的 Forge 1.12.2 客户端桥接模组，以及 Mindcraft 内的 Node.js 适配器。原版 Mineflayer 启动路径保持不变。本阶段不调用 DeepSeek，不启用模型生成代码，不安装模组到现有 Dregora。

## 当前范围

- 玩家位置、视角、血量、饥饿、环境标志、背包、装备、打开容器的槽位。
- 24 格范围内最多 128 个实体的 ID、UUID、注册名、位置、可见性及生命值；不根据注册名猜测敌对关系。
- 视线命中方块的注册名、metadata、状态和坐标。
- SimpleDifficulty 的口渴与体温、First Aid 的八个部位健康、Reskillable 的技能等级。
- 当前客户端加载的物品、方块、配方注册表，分页读取，保留模组命名空间。
- 七种低层动作：停止、短时移动、转向、选择快捷栏槽位、单次近战攻击、使用手持物品、交互视线命中的方块。

模组接口使用反射读取，签名已根据此实例安装的 JAR 核对。缺失模组返回 `unavailable`，读取失败返回 `unknown`，不会用满血、满水等默认值替代。状态来自客户端已同步的数据，并非独立的服务端权威观测。客户端尚未完成同步或其他模组修改了接口时，仍需实际游戏验证。

本阶段尚不提供自动寻路、挖掘、自动合成、容器搬运、自动装备、部位治疗 UI、技能升级、掩护或撤退策略，也未把此适配器转换成完整的 Mineflayer Bot 对象。后续控制器应直接使用这里的接口，不能把它直接传给现有 Mineflayer 插件。

## 构建

使用官方 1.12.2-14.23.5.2860 MDK 的 Gradle wrapper，固定 ForgeGradle 3.0.197，要求 JDK 8。构建缓存与临时工具链位于本目录的忽略目录，不修改全局 Java 设置。

在 Mindcraft 根目录执行：

```powershell
./services/dregora-bridge/build.ps1
npm run dregora:test
```

首次构建需要下载依赖；依赖已有缓存时可使用 `build.ps1 -Offline`。产物：`build/libs/mindcraft-dregora-bridge-0.1.0.jar`。Node.js 适配器只使用内置模块，运行其测试和检查工具无需安装 Mindcraft 的其他 npm 依赖。

## 隔离运行

1. 使用单独的 Dregora 测试实例和测试存档。该实例需要同样的整合包；桥接 JAR 仅放入机器人测试实例的 `mods` 目录。此项目没有自动复制或修改现有实例。
2. 机器人需要真正的 Forge 游戏客户端；登录、连接 LAN 或服务器仍通过客户端完成。此桥接不能绕过账户认证，也不会自动启动第二客户端。
3. 在启动机器人客户端的进程环境中设置 `MINDCRAFT_BRIDGE_TOKEN`，值至少 32 个字符。建议用随机生成的 32 字节令牌。未设置或太短时，桥接完全不启动。
4. 在 Node.js 控制进程中设置同样的令牌。启动器若已经运行，可能不会继承新环境变量，需确保机器人 JVM 实际继承该变量。令牌不应填写到共享文档或提交到 Git。
5. 默认监听 `127.0.0.1:9091`。可用 JVM 参数 `-Dmindcraft.bridge.port=9092` 更换端口；相应修改适配器构造参数的 URL。运行 `npm run dregora:inspect` 可读取当前状态，不执行动作。

游戏运行在机器人客户端中；不要在用于手动游玩的客户端启用桥接控制。失去焦点时暂停的单人游戏、打开菜单、死亡或断开连接会影响控制；这些状态下动作会被拒绝或控制会释放。

## 协议

HTTP 请求均要求 `Authorization: Bearer <token>`。接口仅绑定 IPv4 回环地址，不启用跨域请求，不向浏览器提供令牌。

| 接口 | 内容 |
|---|---|
| GET `/v1/state` | 协议版本、快照时间、世界会话、玩家与模组状态 |
| GET `/v1/catalog?kind=items&offset=0&limit=100` | `items`、`blocks` 或 `recipes` 注册表分页；每页最多 100 条 |
| POST `/v1/actions` | `id`、`session`、`type` 和 `args`；返回排队结果 |
| GET `/v1/actions/<id>` | 查询 `pending`、`completed` 或 `rejected` 结果 |

Node.js 入口为 `src/adapters/dregora/client.js` 中的 `DregoraAdapter`，提供 `getState`、`getCatalog`、`submit`、`getAction`、`execute` 和 `stop`。`capabilityValue` 读取不可用能力时返回 null。

| 动作 | 参数及边界 |
|---|---|
| `stop` | 无参数；取消排队动作，并在下一个客户端 tick 释放控制 |
| `move` | `ticks` 1–20；可选布尔值 `forward/back/left/right/jump/sneak/sprint` |
| `look` | `yaw` -360–360，`pitch` -90–90 |
| `select_slot` | `slot` 0–8 |
| `attack` | `entityId` 和 `uuid`；目标需存活、可见、在 3 格内，且攻击冷却已恢复 |
| `use_item` | `hand` 为 `main/off`，`ticks` 1–100；到期停止持续使用物品 |
| `interact_block` | 无参数；调用当前视线目标方块的主手交互 |

攻击范围目前保守固定为 3 格，不模拟 Spartan 武器或 RLCombat 的完整战斗机制。服务器和模组仍负责执行技能、装备和交互限制。

动作只允许游戏线程执行，排队超过 2 秒过期。重新登录、重生、切换世界或维度会更换会话并取消旧任务。短时移动到期释放控制，菜单或死亡也会释放。最多排队 32 个动作，保留最近 256 个结果；同一个动作 ID 不重复执行，因此只应使用同一 ID 恢复同一个请求。

`completed/dispatched` 仅表示已经交给游戏执行，所有结果当前均包含 `effectVerified: false`。饮水是否增加口渴值、攻击是否造成伤害、配方是否允许执行，需要后续控制器通过新快照验证。客户端配方表不保证完整表达 CraftTweaker 的自定义逻辑、配方形状、NBT 条件或技能限制，所以配方条目标记为 `executable: false`。

HTTP 超时后不能假定动作没有执行。错误中的 `actionId` 可用于查询；不要生成新 ID 自动重发。模型层不得直接绕过这些边界。

## 验证与下一步

自动测试覆盖参数边界、过期状态、世界会话、未知能力值、鉴权、禁止远程地址、动作轮询、拒绝反馈及不确定的网络失败。Java 测试覆盖服务端动作参数校验。

2026-10-07 已在独立 Dregora 客户端完成首轮实机测试，包括模组状态读取、短时移动、停止、一次攻击、食物消耗和箱子交互。测试发现并修复了持续使用物品时未保持使用键的问题。详情与未覆盖范围见 [实机测试报告](LIVE_TEST_REPORT.md)。饮水、部位伤害变化、技能升级和长时间运行仍需验证，之后再增加容器搬运、挖掘与寻路接口，接入固定指令控制器及 DeepSeek 规划。
