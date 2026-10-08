# Visible NPC foundation

Date: 2026-10-07. This is an experimental integrated-server backend, separate from the existing real-player client backend. It needs one game client and no local LLM. Basic rendering, flat-platform navigation, command cancellation and UUID persistence have now been exercised in an isolated Dregora world; see [runtime test report](NPC_RUNTIME_TEST_REPORT.md). Full mod compatibility remains unverified.

## Repository audit and reuse

The inspected branch is `develop`, remote `https://github.com/ZS912719/mindcraft.git`, HEAD `6ad218add8f169ee69022f9e5f5364fa0b4feb46`. The requirement checks, armor action, effect observation and their tests/report were already uncommitted when this work began; they are preserved. No applicable `AGENTS.md` was found in the workspace or its ancestor directories. Runtime logs are now ignored as well as the existing build/runtime-test directory.

Reusable pieces are authenticated loopback HTTP, bounded structured requests, Node transport, registry/ItemStack identity, requirement expression descriptions, observable-effect comparison principles and `src/models/deepseek.js`. DeepSeek is not called by this prototype. The Mineflayer agent loop and its skills must not be given this NPC as a Bot object.

`DregoraBridge`, `CombatState`, `Requirements`, `ActionObservation` and `ModState` use a real player, player controls/containers or player-only mod APIs. They remain the legacy backend. NPC orders use their own server-thread service and sessions; no player eligibility, owner skill level, owner inventory or player attack handler is copied to the NPC.

## Installed mod API audit

These findings come from `javap` signatures and relevant bytecode in the original instance's installed JARs, read without modifying them. They establish API barriers, not a complete runtime compatibility result.

| Mechanism | Local evidence | Consequence for this EntityCreature prototype |
|---|---|---|
| Reskillable 1.13.0 | `PlayerDataHandler.get(EntityPlayer)` and player-bound requirement evaluation | Stack requirement resolution/descriptions can be reused later. Native skill, trait and advancement decisions need an NPC integration design; no fabricated levels or item-name rules. |
| First Aid 1.6.22 | `EventHandler.registerCapability` checks `instanceof EntityPlayer` and explicitly excludes `FakePlayer`; `AbstractPlayerDamageModel.tick` takes an EntityPlayer | No native eight-part health model on this NPC. Its current health is ordinary EntityLivingBase health. A FakePlayer does not solve this barrier. |
| RLCombat 2.2.4 | `Helpers.attackTargetEntityItem(EntityPlayer, Entity, ...)`, `getOffhandDamage(EntityPlayer)`; handler consumes `AttackEntityEvent` | No claim of native cooldown, reach, dual wield or special melee parity. NPC combat remains disabled. |
| Baubles 1.5.2 | `BaublesApi.getBaublesHandler(EntityPlayer)` | Ordinary equipment slots are present, but native accessory slots/effects need dedicated support. Trinkets and Baubles and Bountiful Baubles behavior still need a deeper audit. |
| SpartanWeaponry 1.6.1 crossbow | `findAmmo`/`spawnProjectile` take EntityPlayer; finish/release bytecode guards on EntityPlayer | Setting an active hand on EntityCreature is insufficient. Native ammo ownership, NBT load state, damage and skill gates need an explicit integration. |
| Right-click items / other ranged mods | Player backend invokes the player's controller; standard item right-click takes EntityPlayer | No generic NPC right-click emulation. Switchbow, throwing weapons, shields and continuous weapons each need further investigation and effect tests. |

Do not invoke player-only handlers with the owner to bypass requirements. Any later auxiliary FakePlayer must have an explicit NPC-owned data model and test all side effects; it is not the visible entity. Forge's physical/logical side separation is described in its [1.12 documentation](https://docs.minecraftforge.net/en/1.12.x/concepts/sides/); this module uses a sided proxy for rendering and performs entity mutations on the server thread.

## Implemented scope

The same JAR now contains `mindcraft_dregora_bridge` and `mindcraft_dregora_npc`. The NPC entity is registered as `mindcraft_dregora_npc:teammate`, without natural spawning. It uses a slim-arm player model with an NPC-specific 64x64 skin and a visible name; independent limb textures and clothing overlays were verified in game. Common NPC classes do not reference Minecraft client classes. Dedicated-server and multiplayer operation are not validated or exposed by the prototype bridge.

Each NPC has its own UUID, owner UUID, 27-slot backpack, vanilla living health and six vanilla hand/armor equipment slots. Entity NBT preserves UUID, owner, backpack and native equipment/health. Commands reset to hold on reload. Backpack contents drop on ordinary death when the game's mob-loot rules permit drops. Building-material transfer is available through the test command described below. Backpack UI, general item/equipment transfer, native skills, combat and accessory behavior are not implemented; unsupported capabilities are returned explicitly.

Deterministic server logic provides:

- `follow`: navigate toward the owner within a 32-block local range, stop within 3 blocks.
- `hold`: cancel navigation. This does not provide combat guarding or resistance to knockback.
- `retreat`: navigate to an explicit nearby destination, stop within 2 blocks or after 200 server ticks, then hold. It is not automatic threat-aware route selection.
- `navigate`: follow a local material-aware route to an explicit destination, stop within 1 block or after 1200 server ticks, then hold.
- `summon`: stop navigation, emit portal particles during a 60-tick wait, then teleport to a collision-checked standing surface within horizontal radius 5 and vertical distance 3 of the owner's current position. `hold` or another movement order cancels the wait. The landing position is checked again when the wait ends. This explicitly requested teleport is separate from navigation; follow never teleports automatically.

Lava exposure, low air while submerged, low health while burning, drowning damage and lethal vanilla environmental damage trigger an immediate rescue attempt without the manual wait. A successful rescue clears fire, fall distance, velocity and replenishes air, then holds. Without an available owner or safe landing, rescue cannot guarantee survival and does not grant invulnerability. Unknown mod damage sources are not yet classified.

Death schedules a persistent 600-tick respawn in overworld saved data. Respawn retains NPC UUID, owner and name, restores default health, and uses the same landing search in the online owner's current dimension. Offline/dead/spectator owners or absent safe landings keep the request pending. The countdown uses server game ticks, pauses when the world is paused/stopped and is saved across restarts. Normal death drops remain in force; backpack/equipment are empty on respawn to avoid item duplication. An unloaded living NPC is never inferred dead.

`NpcSummoning.request(EntityPlayerMP, EntityTeammate)` is the shared server-thread entry point for future item interactions or keybinding packets. Callers must derive the player from the authenticated sender; ownership and same-world checks are enforced by the service. Manual summon currently resolves loaded NPCs in the owner's dimension, without the navigation's 32-block range limit. It does not force-load chunks or fetch an unloaded NPC. Standing heights include slab/stair collision geometry. Hazard checks reject vanilla liquid/fire/cactus/magma contact and conservatively reject unknown mod blocks at the landing; full Dregora hazard compatibility remains unverified.

Owner absence/death/spectator state/dimension departure cancels orders. Navigation does not teleport, travel dimensions or force-load chunks. Destinations require loaded nearby blocks, support, collision clearance and a world-border check; vanilla water, lava, fire, cactus and magma destinations are refused. Fire/water path penalties are disabled. These checks do not prove safety against Dregora's other hazards or mobs. `navigation_failed` and `destination_unsafe_or_unloaded` are observable states, never success claims.

## Local construction navigation

`LocalRoute` incrementally searches a voxel graph on the server, without a model call. Each search state includes the standing cell and the hypothetical support blocks already placed. Edges combine cardinal walking, one-block ascent/descent and anchored support placement. A* compares a detour against a bridge or staircase using movement cost 1, ascent surcharge 0.5, descent surcharge 0.25 and construction surcharge 4 per block. This minimizes the configured cost inside this model; it does not claim a globally shortest route through arbitrary Dregora terrain.

Search is bounded to 24 blocks horizontally and 12 vertically from its start, at most 32 new supports, 12,000 expanded nodes and 24,000 states. Each server tick expands at most 128 nodes with a 3 ms search budget. Budget exhaustion is reported and recovery is limited. A moving destination, changed terrain, failed placement or stalled movement can trigger replanning; repeated failures stop the route instead of teleporting or endlessly retrying.

`NpcNavigation` executes each construction and movement step separately. It checks current terrain before walking, uses the native ground navigator for waypoints and carefully approaches a supported edge when a bridge anchor's side is not yet visible. `NpcBuilding` requires a visible reachable anchor, collision clearance and loaded world-border-valid terrain. A temporary NPC-specific FakePlayer invokes native ItemStack use and Forge interaction/placement events; it is only a placement helper, with no owner inventory or skill data. The visible NPC remains the real moving entity. The helper's temporary stack is cleared after use. Only an observed block change consumes the NPC's real material; an inconsistent native result stops navigation as unconfirmed.

Eligible supplies are ordinary vanilla full opaque blocks without NBT, gravity, tile entities or known harmful behavior. Installed Reskillable requirements are resolved from the actual stack at runtime; only a natively empty requirement holder is currently supported. Locked stacks and unavailable requirement APIs fail closed. NPC-owned skills are still a separate milestone. Modded building blocks, slabs/stairs, ladders, digging, buckets, tools and general special-item use are not supported by this first route graph. Existing modded terrain is conservatively blocked.

In the explicit singleplayer cheat test mode, transfer actual owner inventory items within six blocks:

```text
/mindcraft_npc supply <npc-uuid> <player-inventory-slot-0-to-35> <count-1-to-64>
/mindcraft_npc navigate <npc-uuid> <absolute-x> <absolute-y> <absolute-z>
```

Follow and retreat use the same local executor. `hold`, summon and new orders cancel the active route. HTTP snapshots expose `buildingSupplies` and `navigation` status, reason, placed block count, remaining steps, replans, expanded nodes and route cost. Supplying material resets the planner; materials are held in the persisted NPC backpack. No survival GUI or automatic material collection is provided yet.

## Isolated test procedure

Build from the repository using `services/dregora-bridge/build.ps1 -Offline`. The output is `build/libs/mindcraft-dregora-bridge-0.1.0.jar`. Install only in `Dregora AI Test`, with the game exited; do not install in the original instance. The live test installed the corrected JAR only in the isolated instance and created a separate Creative test world; the older DregoraRL test save was not opened.

Start the test JVM with the existing private bridge token and both environment settings:

```powershell
$env:MINDCRAFT_BRIDGE_BACKEND = 'npc'
$env:MINDCRAFT_BRIDGE_TEST_MODE = '1'
```

The launcher must pass these settings to Minecraft. Use an isolated singleplayer world with cheats. The test-mode restriction is intentional for the first prototype; no production survival spawning/ownership UI is supplied yet. NPC commands also work without HTTP if only test mode is set. HTTP needs a valid token. In NPC mode `/v1/actions` is disabled and the legacy client control tick returns before touching keys or camera; use `/v1/npcs`, not `/v1/state`.

On a clear, flat area, run:

```text
/mindcraft_npc spawn
/mindcraft_npc list
/mindcraft_npc follow <npc-uuid>
/mindcraft_npc hold <npc-uuid>
/mindcraft_npc retreat <npc-uuid> <absolute-x> <absolute-y> <absolute-z>
```

Spawn places the NPC two blocks east of the player after destination checks; move if that spot is obstructed. At most eight owned, loaded NPCs may be spawned through this command. This is a local test bound, not a persistent global quota. List/state show owned loaded NPCs in the owner's current dimension only. An absent NPC may be unloaded; it is not automatically respawned or declared dead.

Verify in the actual game before calling the prototype complete:

1. Visible named NPC, movement animation and independent UUID/health; no second client.
2. Follow on flat ground, hold while player keeps moving, and retreat to a clear destination; HTTP command acknowledgment alone is insufficient evidence.
3. Obstruction, unsafe/unloaded destination and excessive navigation range; no navigation teleport or silent success.
4. Owner death, dimension change and logout; no lingering motion order or stale-session execution.
5. Save/reload; same UUID, owner, backpack and equipment, with movement reset to hold.
6. Human input remains functional while menus open/close; `/v1/actions` rejects in NPC mode.
7. Wrong-owner UUID, arbitrary actions and conflicting duplicate command IDs are rejected.
8. Ordinary damage/death and backpack drops; do not infer First Aid support from vanilla health changes.

Keep raw observations, logs and credentials in ignored runtime-test storage. Do not use destructive cleanup commands on unrelated world entities. Use a disposable test world for damage/death and inventory fixtures.

## NPC HTTP and Node interface

All requests reuse the existing bearer authentication and loopback-only listener. There is no NPC spawn or item/equipment mutation over HTTP yet.

For explicitly enabled singleplayer cheat tests only, NPC endpoints can also be queried while the legacy backend runs fixture commands. NPC backend mode continues to reject all legacy player actions. State includes `summonTicks` on loaded NPCs and a separate `respawns` list with UUID, `remainingTicks` and `respawn_pending` status.

| Route | Behavior |
|---|---|
| GET `/v1/npcs` | Server-thread snapshot: session, timestamp, owner dimension and owned loaded NPC state/inventory/equipment/capabilities |
| POST `/v1/npcs/commands` | `{id, session, uuid, command, destination?}`; follow, hold, retreat, navigate and summon |

Sessions change on server restart, owner player replacement, respawn, dimension-change or logout events. Those events also cancel owned loaded NPC navigation, including a dimension change and return between two HTTP reads. Commands are scoped to the authenticated local player's owned NPCs and run only on the integrated server thread. Queued tasks expire after two seconds. The latest 256 accepted command IDs are deduplicated; identical retries return the original acknowledgment, conflicting payloads are rejected. Recovery is bounded by this retention window. A timeout can leave execution uncertain: retry the same ID, session and payload, never generate a new ID automatically.

Successful replies are `completed/command_set` with `effectVerified: false`. Read subsequent NPC position and movement state to assess progress. This protocol has no causal navigation effect verifier yet.

`src/adapters/dregora/npc.js` exports `DregoraNpcAdapter`, which reuses only the legacy transport and keeps player endpoints out of its methods:

```javascript
const adapter = new DregoraNpcAdapter();
const state = await adapter.getState();
await adapter.command(npcUuid, 'follow', { session: state.session });
await adapter.command(npcUuid, 'retreat', {
    session: state.session, destination: { x: 10.5, y: 64, z: 12.5 },
});
```

## Next milestones

Automated validation on 2026-10-07: 28 Node tests passed (22 existing, 6 NPC), 13 Java tests passed (9 existing, 4 NPC), offline build succeeded and tracked-file `git diff --check` passed. NPC tests cover bounded structured commands, unavailable owners, local range/arrival rules, stale sessions, UUID routing, unverified acknowledgments and uncertain transport recovery. Basic rendering and UUID/owner/health persistence were verified in the isolated world. Nonempty inventory persistence, hazard avoidance and full mod lifecycle remain live-test obligations.

Extend the recorded live checks to nonempty inventory persistence, difficult terrain and owner death/dimension transitions. Then implement tested equipment transfer and melee with explicit native-mechanism support; design NPC-owned skills/requirements without assigning owner skill data. Next address accessories, body health, ranged and right-click items individually. Add a game GUI sending the same bounded server commands with sender-derived ownership. Only after reliable deterministic execution should DeepSeek emit low-frequency structured tactical choices. Reinforcement learning remains out of scope.
