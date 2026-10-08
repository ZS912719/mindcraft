# NPC feasibility runtime test

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
