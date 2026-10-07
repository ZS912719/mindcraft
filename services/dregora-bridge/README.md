# Dregora game adapter — first implementation

2026-10-07：新增独立的服务端可见 NPC 原型，保留下面记录的真实玩家客户端后端。NPC 提供 UUID 状态、独立背包和基础跟随／停止／指定位置撤退；已在独立实例完成基础显示、移动、取消和 UUID 重载验证，见 [NPC 实测报告](NPC_RUNTIME_TEST_REPORT.md)；模组技能战斗适配及 GUI 尚未完成。启用 `MINDCRAFT_BRIDGE_BACKEND=npc` 后禁用玩家动作及按键控制。架构调查、限制、协议和独立实例验证步骤见 [NPC foundation](NPC_FOUNDATION.md)。

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
| GET `/v1/test/state` | 仅显式测试模式：读取单人服务器中的测试目标生命值和玩家状态 |

Node.js 入口为 `src/adapters/dregora/client.js` 中的 `DregoraAdapter`，提供 `getState`、`getCatalog`、`submit`、`getAction`、`execute` 和 `stop`。`capabilityValue` 读取不可用能力时返回 null。

| 动作 | 参数及边界 |
|---|---|
| `stop` | 无参数；取消排队动作，并在下一个客户端 tick 释放控制 |
| `move` | `ticks` 1–20；可选布尔值 `forward/back/left/right/jump/sneak/sprint` |
| `look` | `yaw` -360–360，`pitch` -90–90 |
| `select_slot` | `slot` 0–8 |
| `attack` | `entityId` 和 `uuid`；目标需存活、被当前准星命中、在 ReachFix 实际距离内，且攻击冷却已恢复；安装 RLCombat 时调用其攻击入口 |
| `use_item` | `hand` 为 `main/off`，`ticks` 1–100；到期停止持续使用物品 |
| `interact_block` | 无参数；调用当前视线目标方块的主手交互 |
| `equip_armor` | `slot` 0–35；通过生存背包的原生快捷移动穿戴，目标防具槽必须为空 |
| `test_command` | 默认关闭；仅 `MINDCRAFT_BRIDGE_TEST_MODE=1`、单人服务器及作弊权限同时满足时可用；只接受限定的单条游戏指令 |

近战距离来自当前手持装备的有效属性，品质、附魔和其他模组属性仍由游戏计算。范围依据眼睛到准星命中点的距离，不能用玩家到实体中心的距离直接替代。兼容读取失败会报告未知并拒绝攻击，避免绕过模组机制。服务器仍负责技能、装备和交互限制。

状态新增 `combat`（有效距离、攻击强度、有效攻击属性）、`effects`、实体碰撞箱与速度、Baubles 槽位，以及每个物品的 `profile`。物品注册表包含默认堆栈和创造模式公开的变体；这些变体不代表全部可能的 NBT 组合。实体观察区域扩至玩家周围 96 格的轴对齐区域，按距离保留最多 256 个实体，并报告截断；这不是武器射程上限，也不能观察尚未加载的实体。

`src/adapters/dregora/combat.js` 提供独立的 `CombatController`，支持固定意图 `cover/defend/retreat` 和 `useEquipment('block'|'drink'|'self_splash'|'load_crossbow')`。它尚未替换原 Mineflayer 智能体的主循环。掩护和防御目前处理指定目标，不自动挑选敌人；撤退与接近仍需要上层提供经过验证的路线。短撤退最多 5 tick，攻击前会复查世界会话和关键部位健康。

远程动作需要调用方提供实测配置 `rangedProfile`，包含 `weaponKey/ammoKey/equipmentKey/dimension/skillsKey/effectsKey/chargeTicks/samples`。每条成功样本包含 `distance/pitchOffset/hitVerified:true`；只在实测距离区间及极小边界容差内插值，不推断最大射程。弓和弩要求校准弹药位于副手，弩必须先装填；投掷武器使用物品自身的 NBT 状态作为弹药指纹。更换品质、附魔、防具、饰品、技能或药水效果会使配置失效。NBT 中的弹药或装填状态改变也可能要求新配置。移动目标、未知机制和缺少射线净空确认时停止发射。`clearShotVerified` 和 `safeRetreatVerified` 是上层必须提供的判断，不是已有的自动寻路或弹道避障功能。

`use_item` 是持续使用动作，不能把所有特殊道具当成需要长按的弓。饮用需要完成使用时间；自用喷溅药水先向脚下瞄准；弩装填与发射是两个阶段，已装填弩的发射只保持 1 tick；双截棍等连续攻击仍要求专门的动作适配，不会被自动当作普通近战武器。

`combat-lab.js` 的 `CombatLab` 提供目录扫描、生成带 `MindcraftFixture` 标签的静止测试目标、装备替换、近战/远程/使用效果测量。测试功能会修改测试世界，只在独立作弊实例启用。测试角色提升技能至 32 仅用于隔离装备门槛，不证明低等级生存角色能使用同一装备。结果通过 `/v1/test/state` 的服务器生命值验证伤害，失败或未命中也应保存。详情见 [战斗测试报告](COMBAT_TEST_REPORT.md)。

动作只允许游戏线程执行，排队超过 2 秒过期。重新登录、重生、切换世界或维度会更换会话并取消旧任务。短时移动到期释放控制，菜单或死亡也会释放。最多排队 32 个动作，保留最近 256 个结果；同一个动作 ID 不重复执行，因此只应使用同一 ID 恢复同一个请求。

`completed/dispatched` 表示动作已交给游戏执行。攻击、物品使用、方块交互和穿甲会先返回 `pending/verifying_effect`，随后返回 `verification` 及 `effectVerified`。默认轮询等待上限为 8 秒。单人游戏读取服务端动作前后的目标生命值、实际装备、效果、方块和容器窗口；多人游戏只能读取同步的客户端状态，并标记 `source: client_synced`。

攻击只有观察到指定目标生命值下降才确认；穿甲必须观察到请求的防具进入对应槽位；方块交互必须观察到方块变化或容器打开。物品使用只确认手持物品或药水效果的状态变化，不能将其解释为远程命中。无关背包掉落物拾取不算使用成功。没有观察到相应变化时返回 `unconfirmed`，`effectVerified: false`；读取失败返回 `unavailable`。并发的第二个此类动作返回 `action_in_progress`。`stop` 会取消待检查或待验证动作。状态变化仍可能受环境或玩家手动操作影响，不能当作严格的因果证明；未加载目标、持续格挡、特殊模组效果等也可能需要专门的验证器。

客户端配方表不保证完整表达 CraftTweaker 的自定义逻辑、配方形状、NBT 条件或技能限制，所以配方条目标记为 `executable: false`。

### Reskillable 运行时资格

资格来自当前 Reskillable 的 `LevelLockHandler.getSkillLock(ItemStack)`、`PlayerData.matchStats` 和 `PlayerData.requirementAchieved`，不读取配置文件推算等级，也不在适配器里硬编码物品门槛。实际物品元数据、NBT、模组范围锁和动态注册的要求均由原生接口匹配。方块按原生交互处理构造堆栈：方块元数据、无物品形式时的回退堆栈、方块实体原始 NBT；空主手交互还检查副手回退物品。

`/v1/state` 的非空背包物品、主副手 `eligibility` 和 `targetBlock.eligibility` 提供同步客户端预览。真正动作会调用原生要求缓存的 `forceClear` 后重新检查，避免沿用进度撤销或自定义条件变化前的资格：单人模式调度到服务端线程，读取服务端实际物品和玩家要求；客户端与服务端物品不一致或检查后物品／目标变化会返回 `preflight_subject_changed`。多人模式使用同步客户端数据，最终仍由远端服务器执行原生限制。

动作结果中的 `eligibility.subjects` 区分 `item`、`block` 和 `offhand_fallback`，包含完整 `requirements`、缺失的 `missing`、`requirementsMet`、`allowed`、`source` 和创造模式 `bypass`。技能叶子包含 `skill/currentLevel/requiredLevel`；进度包含 `advancement/resolved/achieved`；特质和自定义要求保留原生类型及说明。AND／OR／NOT 等保留 `children` 树，`missing` 返回未满足的完整顶层表达式，不能把 OR／NOT 的叶子平铺成全部必需条件。无法读取时拒绝动作并返回 `requirements_unknown`；明确不达标时返回 `requirements_not_met`。

隔离测试可使用 `test_lock {slot, requirements}` 为带 `MindcraftRequirementFixture` NBT 标记的物品注册临时原生 NBT 条件；表达式由 Reskillable 解析。`test_advancement {granted}` 仅注册／切换固定的 `mindcraft:test_requirements` 进度，定义只存在于测试 JVM 中，用于整合包禁用原版进度时的验证。这两项与 `test_command` 一样要求显式测试模式、单人服务器和作弊权限，不修改整合包配置文件，生产启动默认不可用。

HTTP 超时后不能假定动作没有执行。错误中的 `actionId` 可用于查询；不要生成新 ID 自动重发。模型层不得直接绕过这些边界。

## 验证与下一步

自动测试覆盖参数边界、过期状态、世界会话、未知能力值、鉴权、禁止远程地址、动作轮询、拒绝反馈及不确定的网络失败。Java 测试覆盖服务端动作参数校验。

2026-10-07 已在独立 Dregora 客户端完成状态读取、短时移动、停止、攻击、食物消耗和箱子交互，并修复了持续使用物品时未保持使用键的问题，见 [首轮实机测试报告](LIVE_TEST_REPORT.md)。后续已验证长柄武器、远程武器、部分道具和低温造成的部位伤害变化，见 [战斗测试报告](COMBAT_TEST_REPORT.md)。饮水、自然技能升级、长时间运行、容器搬运、挖掘与自动寻路仍需推进，固定指令 GUI 和 DeepSeek 规划尚未接入。
