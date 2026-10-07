# Dregora live test report — 2026-10-07

## Environment and isolation

- Instance: `C:/Users/sheng/curseforge/minecraft/Instances/Dregora AI Test`.
- Pack: Dregora 1.1.2b, Minecraft 1.12.2, Forge 14.23.5.2860; main menu reported 227 loaded mods.
- Runtime: installed legacy Java 8, local single-player test identity `DregoraTest`, maximum Java heap 6 GB.
- Save: newly created `DregoraRL` inside the test instance; no original save was copied or modified.
- Launch used installed Minecraft/Forge libraries directly and a process-local bridge token. Online account authentication and multiplayer were not tested.
- Test options were backed up before temporarily selecting windowed mode, render distance 8 and no pause on lost focus. The original test options were restored after shutdown.
- No DeepSeek calls were made. No files in the original pack's mods, config or saves directories were changed.

## Results

| Check | Result | Evidence |
|---|---|---|
| Authenticated state endpoint | Passed | Connected player, live coordinates, inventory and equipment snapshots |
| Unauthorized access | Passed | Request without bearer token returned HTTP 401 |
| SimpleDifficulty capabilities | Passed for reading | Thirst level 20 and saturation 5; temperature changed from 11/NORMAL to 8/COLD during the session |
| First Aid capability | Passed for reading | Eight body parts; head and body marked critical, health/max health reported as 6/6 |
| Reskillable capability | Passed for reading | Eight skills reported at level 1 |
| Runtime registries | Passed | 4,577 items, 2,045 blocks, 4,801 recipes; bounded pages returned entries |
| Look action | Passed | Later snapshot confirmed yaw 30 and pitch 10 |
| Hotbar selection | Passed | Later snapshot confirmed selected slot 2 |
| Bounded movement | Passed | Five movement ticks produced approximately 1.073 blocks of displacement, followed by zero observed drift |
| Stop action | Passed | Active movement was stopped; later snapshots showed zero horizontal drift |
| Empty-hand item use | Passed | Explicit `empty_hand` rejection |
| Menu interlock | Passed | Movement submitted while the pause menu was open returned `game_not_controllable` |
| Session isolation | Passed | Pre-restart session request returned HTTP 409 / `stale_session` |
| Food consumption | Passed after fix | Apples decreased from 3 to 2; food increased from 13 to 17 |
| Single melee attack | Passed | Stationary pig health decreased from 10 to 9, confirmed by UUID-matched snapshots |
| Block interaction and container | Passed | Ray target identified the test chest; interaction opened window 1; slot 0 contained the two preset apples, matching the visible GUI |

Capabilities being readable does not establish that every server-side change or custom mechanic is fully synchronized. Thirst depletion/drinking, damaged body-part updates, skill progression and unsupported mod interfaces require additional tests.

## Bug found and fixed

The first food test returned `completed/dispatched`, but the apple count and food level did not change. Minecraft stopped using the item because the use key was not held between ticks.

The bridge now holds the use key for the bounded action duration and releases it on stop, timeout, menu entry, death or session reset. It also releases prior controls before starting item use. The rebuilt bridge was installed only in the test instance and the same save was reopened for the successful food test.

Action responses still correctly report `effectVerified: false`. The external test harness verified effects using subsequent observations; the bridge does not automatically claim semantic success.

## Resource observation

One in-world sample showed a Java process working set of approximately 6.76 GB and only 0.89 GB of free system memory. This is a point-in-time observation, not an average or a two-client benchmark. Inherited shaders were active. Do not use this run as evidence that two Dregora clients will run comfortably on 16 GB RAM; repeat with shaders disabled and a measured memory budget before attempting that configuration.

## Test artifacts and cleanup

Raw snapshots, action results, isolated launch scripts, the old bridge JAR and logs are kept locally under `build/runtime-test/`, which is excluded from Git. The local bearer token is stored there for test reuse and must not be shared or committed.

The test world was saved and the test JVM was shut down. The test instance retains the rebuilt bridge and dedicated save. The source fix and this report are not automatically committed or pushed.

## Remaining validation

- Drinking from blocks and containers, thirst depletion, temperature protection and First Aid healing interfaces.
- Skill locks/progression, mod weapons and equipment interactions.
- Item pickup: the `/give` apple fixture stayed on the ground instead of appearing in the inventory; explicit pickup behavior needs separate investigation. The food fixture used `/replaceitem` to avoid conflating pickup and consumption.
- Death/respawn, dimension changes, network disconnects and sustained operation.
- Actual item transfer, digging, crafting, pathfinding, cover and retreat controllers, which are outside the current adapter's implemented action set.
- Two-client multiplayer and performance testing.
