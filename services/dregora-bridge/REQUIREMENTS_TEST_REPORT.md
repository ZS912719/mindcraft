# Runtime requirement and action verification tests

Date: 2026-10-07. Environment: the isolated `Dregora AI Test` CurseForge instance, Minecraft 1.12.2, Forge 14.23.5.2860, RLCraft Dregora 1.1.2b, Reskillable 1.13.0 and RLCombat 2.2.4.

The original Dregora instance's mods, configuration and saves were not modified. All game fixtures were created in the test world with explicit bridge test mode and cheats enabled. Runtime NBT locks used Reskillable's own registration and parser APIs; no persistent configuration rules were added.

## Implementation exercised

- Resolve the actual stack through `LevelLockHandler.getSkillLock`, including metadata, NBT and dynamically registered locks.
- Evaluate the native holder and each requirement through `PlayerData.matchStats` and `requirementAchieved`. Action preflight clears the player's native requirement cache before evaluation, including after advancement revocation.
- Preserve logical trees. An unsatisfied OR or NOT expression is returned as a whole expression, rather than flattening its leaves into mandatory requirements.
- Check the integrated-server player's actual inventory stack and reject mismatched client/server stacks. Recheck the client subject and block fingerprint before dispatch.
- Reproduce native block stack construction, including raw tile entity NBT and the empty-mainhand offhand fallback.
- Observe server effects after dispatch. Attack wear alone, unrelated inventory pickups, and a dispatched block click alone do not prove success.

## Recorded live scenarios

Twenty-five scenarios were recorded during the implementation. The following table groups their expected and observed outcomes.

| Scenario | Native requirements / conditions | Observed result |
|---|---|---|
| Iron sword | Attack 7 / required 8 | Rejected with current and required levels |
| Iron sword | Attack 8 | Accepted; server target health decreased |
| Diamond helmet | Defense 15 / required 16 | Rejected; no armor action dispatched |
| Diamond helmet | Defense 16 | Accepted; the requested helmet appeared in the head slot |
| Bow | Agility 1 / required 2 | Rejected before use |
| Bow | Agility 2 | Accepted; bow/ammunition state changed, not treated as proof of projectile impact |
| Brewing stand placement | Building 7 and Magic 7 / both required 8 | Rejected; both missing conditions returned |
| Brewing stand placement | Building 8, Magic 7 | Rejected; only Magic remained missing |
| Brewing stand placement | Building 8, Magic 8 | Accepted; server block state changed to a brewing stand |
| Placed brewing stand | Building 8, Magic 7, empty hands | Rejected on the block's requirement, not a held-item name |
| Placed brewing stand | Building 8, Magic 8 | Accepted; server container window changed and the brewing GUI opened |
| NBT-marked wooden sword | Attack 8, Magic 7; runtime NBT lock requires both 8 | Rejected with Magic missing |
| Same item ID, unmarked sword | Attack 8, Magic 7 | Accepted; server health decreased |
| NBT-marked wooden sword | Attack 8, Magic 8 | Accepted; both native requirements remained present and achieved |
| Native OR expression | Magic 7, Building 7; either must reach 8 | Rejected with a complete OR tree |
| Native OR expression | Magic 7, Building 8 | Accepted despite the unsatisfied Magic leaf |
| Native NOT expression | NOT Magic 8, current Magic 7 | Accepted |
| Native NOT expression | NOT Magic 8, current Magic 8 | Rejected; the achieved child stayed inside the unsatisfied NOT tree |
| Skill plus advancement | Attack 8; fixture advancement incomplete | Rejected; server health stayed 1000 -> 1000 |
| Skill plus advancement | Fixture advancement granted | Accepted; server health fell 1000 -> 996 |
| Skill plus advancement | Previously granted advancement revoked | Rejected after a fresh native check; health stayed 996 -> 996 |
| Fishing rod | Native UnobtainableRequirement | Rejected with an explicit unusable requirement |
| Skill downgraded to Attack 7 | Iron sword | Rejected after clearing native eligibility cache |
| Creative mode | Attack 7; native creative enforcement disabled | Accepted with `requirementsMet: false`, `allowed: true`, `bypass: creative`; target health decreased |
| Empty mainhand, bow in offhand | Agility 1; eligible brewing stand target | Rejected by the `offhand_fallback` requirement |

The brewing stand subject included its actual tile compound (`Fuel`, `BrewTime`, `Items`, position, ID and Forge capabilities). The NBT-driven lock test used a marked item stack; a separate custom tile-NBT lock was not registered.

## Advancement fixture and development observations

Dregora enables FermiumMixins `Nuke Advancements (Vanilla)`. An initial lock referencing `minecraft:story/smelt_iron` remained unsatisfied because that advancement was absent. The bridge reports the ID and `resolved: false` and does not manufacture eligibility.

Positive advancement tests used the fixed `mindcraft:test_requirements` definition, registered only in the test JVM. Grant and revoke operate on real server `PlayerAdvancements`. The definition disappears when the process exits. Native initialization removes resolved entries from its supplied map, so the test helper supplies a mutable map.

During fixture preparation, a block placement was dispatched while a recently defeated target still obstructed placement. The block did not change, and the verifier correctly returned `unconfirmed`, even though an unrelated dropped item was picked up. After removing the obstruction, placement succeeded. A second fixture issue was corrected by aiming at the brewing stand's low native selection box instead of its visual upper stand.

After cleanup returned the test player to its original position, a natural zombie attack killed it while the game was still running. The test player was respawned and protected, its inventory and skills were restored again, and the nearby zombie and test death drops were removed. The living character was then saved and the client exited. This did not affect the original Dregora save.

## Automated checks

- `npm run dregora:test`: 22 tests passed.
- `services/dregora-bridge/build.ps1 -Offline`: build succeeded; 9 Java tests passed.
- Verification tests cover damage evidence, exact requested armor, block/container evidence and unrelated inventory pickups.
- Adapter tests preserve compound missing conditions, require evidence for `effectVerified: true`, and bound armor slots and test lock inputs.
- The native advancement loader regression test registers a real fixture and verifies that its mutable input map is consumed without the initialization exception observed during development.
- `git diff --check`: passed.

Local raw observations are kept in ignored `build/runtime-test/requirements-results.json`; tokens and launch credentials are not committed.

## Scope and limits

The integrated-server checks are authoritative for the tested singleplayer setup. Multiplayer qualification and effect observations use synchronized client data, with the remote server retaining final enforcement. A remote server counterpart has not been tested.

`effectVerified` means the specified observable change occurred. Item-use verification does not prove a ranged hit. Continuous blocking, hidden custom effects, unloaded or dead targets, trait-specific scenarios and arbitrary third-party requirement subclasses may need additional dedicated tests. Custom requirement decisions still come from the native implementation; unreadable requirements reject the action.

Test inventory, skill levels, position, temporary blocks, tagged targets and item drops were restored or removed. The test world was saved and exited; temporary display/shader settings were restored. Other incidental test-world progression, such as experience collected from fixtures, is not an exact world rollback.
