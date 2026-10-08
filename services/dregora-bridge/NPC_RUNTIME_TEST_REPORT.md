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

This does not establish First Aid, Reskillable, RLCombat, accessory, equipment-use, ranged or special-item compatibility. No NPC combat, skill qualification, GUI commands or AI planning is claimed. Tests with actual inventory contents, death/drop behavior, difficult terrain, owner death/dimension transitions and multiplayer remain pending.

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

The actual game refused navigation across a three-block gap with no building supplies. No material was invented or consumed, the NPC stayed alive, and bounded recovery ended in `navigation_failed`. Supplying plain cobblestone then exposed an API interpretation defect: Reskillable `RequirementHolder.hasNone()` is a special native marker, not an empty requirement list. Installed-mod bytecode confirmed the distinction; NPC eligibility now reads the actual `getRequirements()` collection. The corrected offline build and all 24 Java tests passed. The owner confirmed saving and exiting the world; the verified test process was stopped, the corrected JAR installed and its SHA-256 checked against the build. Native construction/traversal still needs validation after the next launch.

Temporary corridor/gap fixtures remain in the saved disposable world, with the original NPC holding and the test player alive in Creative mode. The test player's previously empty hotbar slot contains the 32 fixture cobblestone blocks that the earlier build refused to transfer. All three test option files were restored from backups and SHA-256 verified, including restoring the original pause-on-lost-focus setting. Bridge runtime fixtures and observations remain private under ignored build storage. Compilation and synthetic route tests do not establish actual native placement or safe traversal. Bridge, ascent, detour, construction cancellation and changed-terrain checks are prepared but have not passed in game yet. No LLM is connected.
