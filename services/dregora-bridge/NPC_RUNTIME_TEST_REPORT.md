# NPC feasibility runtime test

Latest development note (2026-10-23): lifecycle and collision-walking hardening results are recorded separately below. Earlier live results describe their original tested builds and must not be read as validation of subsequent code.

Date: 2026-10-07. Instance: `Dregora AI Test`, Dregora 1.1.2b, Minecraft 1.12.2, Forge 14.23.5.2860. New disposable world: `MindcraftNPC-Test-20261007`, Creative mode with cheats enabled. No LLM or DeepSeek calls were made. Original-instance mods, configuration and saves were not changed.

## Observed behavior

- The NPC module loaded in one full game client; no second AI client was needed.
- `/mindcraft_npc spawn` created one owned server entity with UUID `25077dfc-bd02-47c2-b234-2d00e8364815`, vanilla health 20, independent inventory/equipment state and initial hold behavior.
- The test area was a manually prepared stone platform in the new world. This verifies movement on a simple surface, not Dregora terrain safety.
- Retreat to `(1461.5, 66, -297.5)` changed the NPC position from approximately `(1471.414, 66, -297.060)` to `(1463.293, 66, -297.619)` and returned it to hold near the target. The initial HTTP acknowledgment correctly kept `effectVerified: false`.
- Follow then moved the NPC back toward the owner, to approximately `(1466.208, 66, -296.937)`, with `movement: arrived`.
- A stale session, absent/non-owned UUID, unsupported attack, distant retreat and unsafe y=0 destination were rejected.
- Identical command ID/payload retries returned the original result. Reusing that ID for follow after hold was rejected with `command_id_conflict`.
- The legacy player action route rejected movement with `player_backend_disabled`.

## Defect found and correction

The entity was visible, but the initial renderer showed missing/mistextured body and limb regions. `ModelBiped()` defaults to a 64x32 atlas while the selected Steve texture is 64x64. The renderer was changed to `new ModelBiped(0.0F, 0.0F, 64, 64)` and rebuilt. The test world was saved, the game exited, and only the isolated instance's bridge JAR was replaced. The corrected build was verified in game: the Steve body, arms and legs render completely.

The startup trial also encountered a local HTTP bind conflict. Testing uses port 9093. This was a listener initialization failure, not evidence of NPC behavior failure. The current bridge still requires an unused configured port.

## Evidence and limits

Private observations and launch material are under ignored `build/runtime-test/`; no credentials, raw logs or temporary scripts are part of this report. Compilation and all 13 Java tests passed after the renderer correction. Node tests were previously 28/28; the correction changes only the Java renderer.

This does not establish First Aid, Reskillable, RLCombat, accessory, equipment-use, ranged or special-item compatibility. No NPC combat, skill qualification, GUI commands or AI planning is claimed. Nonempty backpack persistence and controlled construction terrain were subsequently tested below; nonempty death/drop behavior, natural difficult terrain, owner death/dimension transitions and multiplayer remain pending.

## Reload and cancellation checks

After saving, fully exiting the process, replacing the JAR and loading the same world:

- The exact NPC UUID, owner UUID, health 20 and saved position were preserved; command/movement were hold/holding.
- The server session changed, and a command carrying the actual pre-restart session was rejected with `stale_npc_session`.
- State still reported 27 backpack slots and six equipment slots. These were empty, so persistence of nonempty item contents has not been proved.
- Retreat was interrupted with hold after 700 ms. After a 500 ms settling interval, the NPC's position drift over the next two seconds was exactly zero in the sampled server states.
- The corrected NPC model was directly visible with complete skin/body/limbs and a name/health display. A local visual record is kept in ignored runtime-test storage.

## Custom skin verification

The teammate now uses the user-supplied 64x64 skin at `assets/mindcraft_dregora_npc/textures/entity/teammate.png`, with `ModelPlayer(0.0F, true)` for slim arms, independent limb textures and clothing overlays. The resource is byte-identical to the supplied PNG and was confirmed inside the built JAR. The offline build and all 13 Java tests passed. After installing only in the isolated instance and loading the same NPC test world, the new skin and complete body were directly observed. A screenshot is retained in ignored runtime-test storage. No AI service was connected.

## Summoning and respawn checks (2026-10-08)

The same isolated Creative test world was used without connecting an LLM. Explicit cheat test mode temporarily enabled legacy fixture commands alongside NPC observation. The following game checks passed:

- Manual summon remained in countdown after one second, then reached a landing inside the requested horizontal radius 5 / vertical distance 3 after the 60-tick wait.
- Hold canceled an active countdown, with no later teleport.
- A temporary lava pool triggered immediate rescue; the NPC survived at a safe location near its owner.
- A temporary covered water pool triggered low-air rescue; the NPC survived near its owner.
- Instant-damage testing created a pending respawn record. The NPC was still absent before the 600-tick deadline, then respawned with its original UUID, health 20 and empty backpack near the owner.
- Summoning while the owner was above terrain with no nearby support was rejected. Keeping the owner there during an existing countdown also prevented teleport, proving the completion-time landing recheck.
- Portal particles were directly observed in game.

Temporary fluid pools and their glass ceiling were cleared, and the test player's position/orientation was restored. No extra NPC was created. The respawn queue's UUID/owner/deadline NBT round trip passed automated testing; a full process restart while a respawn is pending remains untested. Nonempty death drops, mod-specific lethal damage, multiplayer and item/key bindings remain unverified. Final hardening bounds failed nonlethal environmental rescue searches to one attempt per 20 ticks, while lethal damage can trigger an immediate retry. Offline build, 17 Java tests and 29 Node tests passed; no logs, credentials or temporary fixtures are included in tracked files.

## Final environment

After summoning tests, the world was saved and fullscreen restored. Closing the remaining client was initially rejected by automatic approval review. The owner subsequently authorized closing/restarting the test client; its recorded PID and start time were verified before shutdown. The original NPC remains alive with its original UUID and holds near the player; no respawn remains pending. The new test world and prepared platform are retained for continued testing. The original instance and older DregoraRL test save were not modified.

## Construction navigation validation (2026-10-08)

The local planner and executor now support ordinary movement, obstacle detours, anchored bridge supports and ascending support steps. Automated validation: 24 Java tests, 30 Node tests, offline build and tracked-file `git diff --check` passed. Route tests cover a three-block gap requiring exactly three supplies, a three-block rise requiring two supports when the cliff itself provides an anchor, insufficient supplies, short free detours, choosing construction over a more expensive detour, blocked terrain, overhead ascent clearance and incremental search yielding.

The navigation JAR was installed only in `Dregora AI Test`. Two client launches failed with an OpenGL context error before opening a world. Temporarily disabling fullscreen/shaders and reducing render distance allowed startup to reach the Dregora window, but Windows input failed with `GetCursorPos` access denied, including after tool reset. The owner manually opened the same test world and disabled pause on lost focus so bridge tests could proceed.

The actual game refused navigation across a three-block gap with no building supplies. No material was invented or consumed, the NPC stayed alive, and bounded recovery ended in `navigation_failed`. Supplying plain cobblestone then exposed an API interpretation defect: Reskillable `RequirementHolder.hasNone()` is a special native marker, not an empty requirement list. Installed-mod bytecode confirmed the distinction; NPC eligibility now reads the actual `getRequirements()` collection. The corrected implementation transferred 32 actual owner-held blocks into the NPC backpack and emptied the source slot.

Live construction exposed two executor defects. Edge steering was refreshed only once every five ticks although the native movement helper consumes its target each tick; the NPC could not reach a visible placement face. The executor now continuously approaches the supported edge with collision/support checks and a bounded timeout. After placement, an overly narrow waypoint threshold could ask the native navigator to recover from the same bridge edge and waste the replan budget. Arrival now recognizes a grounded NPC inside the target standing cell less than 0.5 blocks from its center (squared distance below 0.25). Both changes were rebuilt, installed only in the isolated instance and tested after full client restarts.

The final build passed this live matrix. Every case preserved health 20. Construction evidence combines actual NPC positions, exact real-backpack deductions and independent client ray traces of the resulting cobblestone blocks; an HTTP acknowledgment alone was never treated as completion.

| Scenario | Observed result |
|---|---|
| Five-cell gap | Reached the opposite shore; exactly five blocks consumed and independently observed; zero recovery replans. |
| Three-cell gap, east/west/south/north | All four directions reached the destination; exactly three supports per case, independently observed; zero recovery replans. |
| Three-block rise beside an existing cliff | Reached the upper destination using exactly two additional supports, independently observed; two bounded recovery replans. |
| Two-block-high obstacle | Walked around it without consuming material. |
| Obstacle inserted after route selection | Replanned once and reached the destination without consuming material. |
| Hold during construction | After the first block was consumed, hold stopped further placement; after a 500 ms settling interval, displacement remained below 0.02 blocks over two seconds. |
| Follow across a three-cell gap | Built three supports and stopped within the owner's three-block arrival radius; sampled movement did not report summoning. |
| Retreat across a three-cell gap | Built three supports and reached the two-block arrival radius within the existing 200-tick timeout. |
| Save and full process restart | Preserved the same NPC UUID, owner, health 20 and a nonempty backpack of 30 cobblestone blocks. |

The first follow fixture expected two supports, but the executor legitimately placed the third support before entering its arrival radius. The fixture expectation was corrected to the full three-cell bridge, then follow was rerun successfully; this was not a production-code change. An additional real transfer of 32 blocks increased supplies from 15 to 47 before the final matrix. After all trials, 18 unused fixture cobblestone blocks remain in the NPC backpack; the owner's source slot is empty.

Temporary bridges, corridors, ascent blocks and obstacles were removed. The artificial test platform was refilled with stone through the gap depth, and the player's original position/orientation was restored. This is fixture cleanup, not an exact rollback of the original terrain, world time or other world state. The original NPC is alive, holding near its owner with health 20 and no pending summon. Bridge observations and launch material remain private under ignored build storage. No LLM is connected.

These results cover controlled Creative/cheat fixtures using plain vanilla cobblestone. They do not establish arbitrary natural-terrain optimality, survival material gathering, modded block placement, digging, ladders, owner lifecycle transitions, multiplayer or player-only mod mechanics. The ascent recovery behavior still merits longer and more varied terrain testing. The route graph remains bounded and can stop with a reported failure.

The owner confirmed saving and exiting to the title screen after cleanup; the bridge reported the world disconnected. The recorded test JVM's PID, start time and isolated-instance path were verified before shutdown. All three option files (`options.txt`, `optionsof.txt`, `optionsshaders.txt`) were restored from this run's pre-test backups and SHA-256 verified. The installed bridge JAR matches the final tested build by SHA-256. Original-instance mods, configuration and saves were not modified.

## Follow distance recovery implementation (2026-10-08)

A subsequent change adds immediate follow-only teleport recovery at three-dimensional distances from 12 to 32 blocks inclusive. It reuses the collision-checked summoning landing search and particles, keeps the follow order, and throttles attempts to every 20 ticks. Beyond 32 blocks, a loaded follower stops with `outside_local_range`; distance alone no longer cancels its follow order. Manual summon remains available with its existing countdown. No safe landing means ordinary navigation continues. Leashed, riding or passenger-carrying NPCs do not receive distance recovery; environmental rescue remains independent.

The offline build and all 26 Java tests passed. New automated checks cover both distance boundaries, nonfinite distances, unavailable owners, other orders, retry delay and restrained entities. The earlier 30 Node tests apply to unchanged Node code. The new JAR was subsequently installed only in `Dregora AI Test` and the owner opened the same disposable Creative world. Windows input still failed with `GetCursorPos` access denied; screenshot capture was available, but entering and saving the world required the owner. No AI service was used.

Live observations passed the following matrix. The same original NPC retained its UUID/owner, health 20, all 18 cobblestone blocks and unchanged equipment in every case. Position changes and later server snapshots were used as evidence, not command acknowledgments.

| Scenario | Observed result |
|---|---|
| Owner 11 blocks away | Ordinary walking; no teleport-sized position jump during the 1.3-second sample. |
| Owner moved 20 blocks away during follow | NPC moved near the owner within 157 ms of the fixture request; follow remained active, with no summon countdown. |
| Owner moved 31 blocks away during follow | Recovery within 170 ms; follow remained active. Both recovery landings were one horizontal block from the owner, at the same height. |
| Owner moved another six blocks after recovery | NPC walked after the owner and stopped within three blocks; follow remained active. |
| Owner 40 blocks away | NPC remained stationary during two seconds of sampling, retained follow and reported `outside_local_range`. |
| Manual summon from that 40-block separation | NPC remained at its old location after one second with a positive countdown, then landed near the owner and held after the normal wait. |
| Hold with owner 20 blocks away | No distance recovery or movement. |
| Owner's entire candidate landing area covered in magma | No unsafe teleport or material loss across 2.3 seconds; follow reported `destination_unsafe_or_unloaded`. |
| Owner's candidate landing volume filled with stone above the vertical landing limit | No unsafe teleport across 2.3 seconds; follow remained active and reported `destination_unsafe_or_unloaded`. |

The measured times include local fixture/bridge request overhead and are examples, not a latency guarantee. Failed landing cases establish safe refusal across multiple ticks; the exact retry interval is covered by policy tests rather than a runtime search counter. Portal particles reuse the previously live-verified summoning routine; this run did not separately capture the brief recovery particle animation. Exact threshold equality, leash/rider/passenger exclusions, other dimensions and multiplayer have not been exercised in game. The construction matrix above describes the earlier build; its follow case used distances below the new recovery threshold.

The temporary magma and blocked volumes were removed, the extended artificial test floor was restored to stone, and the player's original position/orientation was restored. This expanded the existing disposable stone platform and does not constitute exact natural-terrain rollback. Cleanup left the original NPC holding nearby with health 20 and 18 cobblestone blocks. Private test scripts and observations remain ignored; no credentials or raw logs are included in tracked files.

The owner confirmed saving to the title screen, and the bridge reported `connected: false`. The test client's recorded PID/start time and isolated-instance path were checked before shutdown. All three display/option files were restored from this run's `follow-before-*` backups and hash verified. The installed JAR matches the tested build by SHA-256. Original-instance content was not modified. No new production-code fix was needed during this live matrix.

## Lifecycle and natural-terrain hardening (2026-10-22–23)

This build adds immediate owner-death session invalidation, cancellation before native AI advances, chunk-unload cancellation, throttled respawn landing searches and sanitized respawn-record loading. It also adds collision-checked native walking candidates for vanilla partial surfaces and plants, with loaded-world/hazard/support checks and bounded per-edge ascent/descent. Both walking and construction executors issue single checked waypoints to prevent unchecked native shortcuts.

Offline build and 32 Java tests passed with zero failures/errors. New regression coverage exercises slab/snow/single-block slope geometry, low ceilings, two-block drops, unsupported edges, diagonal corner obstruction, changed terrain, supported stepping transitions and malformed respawn records containing inventory/equipment tags. The 31 Node tests include the test-only `spreadplayers` allowlist boundary. These automated tests complement the actual game checks below.

A separate save, `MindcraftNPC-Reliability-20261022`, was copied from the stopped test world's save and given a distinct display name. Only the isolated test instance received the new JAR. The original instance and the previous NPC test save were not opened or edited. Display options were backed up before temporarily disabling fullscreen/shaders, reducing render distance and disabling pause on lost focus. The owner entered the new save after Windows input again returned `GetCursorPos` access denied.

Slab/stair stepping initially failed safely: the native body rises before its center crosses the riser. Planned waypoints still require center support; moving bodies now use actual footprint support within a bounded checked edge. Slabs and stairs passed after correction and full client restart.

The initial snow fixture assumed a collision height of 0.125. Installed two-layer snow actually placed the player's feet 0.0625 above the floor; the fixture now measures actual feet height. Snow traversal then passed. Checked walking edges allow up to 1.25 blocks for a block plus thin cover; the construction graph still uses one-block integer steps.

The initial dimension fixture exposed an uninitialized target world and a vanilla teleporter assumption about prior portal state. The test-only command now initializes the dimension and uses custom Forge `ITeleporter` placement. Native player dimension events were subsequently tested; vanilla portal entry/exit placement and NPC dimension travel were not. Snapshots/commands reject inconsistent entity/world dimensions and absent world-member owners. Intermediate interrupted engine states were excluded from navigation results.

A refused natural snow route stood on `biomesoplenty:grass`. Read-only inspection of the installed BOP build found no added entity-contact damage behavior in ordinary grass/dirt implementations. Walking/standing now admit only those exact classes with runtime build `7.0.1.2445`; other unknown mod implementations remain refused. This does not admit modded building supplies or bypass runtime requirements.

### Verified runtime results

| Scenario | Evidence |
|---|---|
| Plants, slabs, snow, stairs, five-block stepped hill and ice | Final installed build reached every specified destination within one block, retained health 20 and unchanged inventory. Actual positions were rechecked from observations on October 23. |
| Obstacle added during execution | Replanned and reached the destination with unchanged health/inventory. |
| Construction regression | Four cardinal three-cell gaps consumed three real blocks each; five-cell gap consumed five with zero recovery replans; three-block rise consumed two. Independent client ray traces confirmed supports. These checks preceded the final BOP terrain allowance; construction-material eligibility was not broadened afterward. |
| Follow recovery regression | Final build passed walking at 11 blocks, recovery at 20/31, continued following, waiting at 40, delayed manual summon, hold, and magma/blocked landing refusal. Identity, health and contents were preserved. |
| Nonempty full-process reload | Same UUID/owner preserved 18 cobblestone and a leather helmet; a later reload also preserved 50 supplies. Movement reset to hold. |
| Owner death and respawn | Actual death/respawn changed sessions, canceled the NPC order and preserved its contents. Raw server POST rejected old-session commands independently of the Node client's session guard. |
| Overworld → Nether → Overworld | Actual dimension/world fields agreed; away dimension had no owned NPC; original NPC remained in the Overworld holding. Server rejected pre-transition session. Custom placement fixture only. |
| Pending NPC respawn across full process restart | Saved NBT contained one pending record and its exact 600-tick deadline. After save/quit/restart, 24 observations showed the same UUID/owner respawn at or after that deadline with health 20 and empty equipment/backpack; pending record removed. Countdown paused while the world was stopped. |
| Actual chunk unload/reload (October 23) | Owner moved 512 blocks away; original NPC disappeared from the server's loaded list with no pending respawn. After return, the same UUID/owner reappeared holding with unchanged health, backpack and equipment. Its age counter restarted. A bridge timeout during return was resolved by observation only, without repeating the teleport. |
| Known unmodified BOP snow slope | Walked over four blocks from feet height 93.125 toward height 91 and arrived within one block. No damage or material consumption; two replans. |
| Three grounded natural follow samples | Two actually walked about 4.36 and 4.42 blocks and arrived; one safely refused an unsafe/unloaded destination. Samples observed walking, with maximum sampled hops about 0.32 and 1.02 blocks. Owner distances stayed below automatic recovery range; teleport was not counted as walking. No damage/material consumption. |

Earlier surface fixtures started at health 19 and preserved it; final regressions followed normal NPC respawn at health 20. NPC death occurred with 31 fixture supplies and a helmet equipped. Respawn correctly restored no items; exact ground-drop counts were not independently established.

### Scope and remaining limits

Successful natural routes sometimes used two or three bounded replans. Three terrain samples and one known slope do not establish arbitrary natural-terrain optimality or reliability. Unknown mod surfaces outside the two audited BOP implementations, mod-specific hazards, longer mixed ascents, foliage changes, native portal placement, leash/rider exclusions and multiplayer need focused validation. First Aid, NPC-owned skills, accessories, combat, ranged and right-click item mechanics remain separate adaptation work. No LLM was connected.

Known liquids, fire, cactus, magma, TNT, slime, web and portal surfaces are refused by shared geometry checks. This is not comprehensive Dregora hazard detection. Failure states remain observable and must not be interpreted as success. The copied world has changed time, death/experience and generated dimension state; fixture cleanup is not an exact save rollback. Private fixtures, launch files, logs and credentials remain ignored.

On October 23, reopening found the NPC alive at health 18.5 rather than the prior recorded 20. The owner subsequently confirmed causing this damage; it is not an unexplained environmental-damage result. Chunk unload/reload and cleanup retained 18.5. This observation does not establish complete hazard protection. Final state: original UUID/owner, hold near the restored player position, no supplies, empty equipment and no pending respawn. No unrelated entities/items were deleted; exact death-drop cleanup is not claimed.

Artificial bridges, gaps, slopes, snow/ice fixtures, dynamic obstacles, magma and blocked landing volumes were cleared, and the stone test floor and original player position/orientation were restored. This is fixture cleanup only. The dedicated reliability copy was saved and the client exited normally. All three option files were restored from this task's `reliability-before-*` backups and SHA-256 verified; the installed JAR matches the tested build. Original-instance mods/config/saves and the previous NPC test save were not modified. Final Node rerun passed all 31 tests; Java result files confirm 32 tests, zero failures/errors. `git diff --check` passed.
