# Dregora combat adaptation tests

Date: 2026-10-07. Instance: `Dregora AI Test`, local integrated server, Forge 1.12.2-14.23.5.2860. The original Dregora instance's mods, configuration and saves were not modified.

## Method

The runtime catalog contained 4,577 registered item IDs. The catalog exporter recorded default stacks, creative variants, item classes, use actions, attribute modifiers and selected mod APIs. Enumeration is not an effect test and does not cover arbitrary enchantment, quality or potion NBT combinations.

A stone arena was created only in the test world at y=180. Tagged, stationary pigs with 1,000 health served as targets. The test player used survival mechanics, with skills temporarily set to 32 and resistance applied. Cold injury nevertheless occurred; cold resistance and healing were subsequently applied. Successful attacks were verified using the integrated server's target health, rather than client-side attack prediction. All native action results retained `effectVerified:false`; the laboratory computed verification separately.

QualityTools assigned different random qualities to generated equipment. An initial pike reported 5.5 reach, while another reported 4. Neutral-quality fixtures used `{Quality:{}}` for controlled comparisons. An initial command-coordinate centering offset was corrected before the neutral tests. Tables below use the corrected fixtures.

## Measured results

| Equipment | Target center distance | Effective melee reach | Server target health | Outcome |
|---|---:|---:|---|---|
| Iron sword | 2.5 | 4 | 1000 → 994 | Hit |
| Iron sword | 4.5 | 4 | 1000 → 1000 | Rejected by reach/aim check |
| Iron spear | 4.2 | 4.5 | 1000 → 993.5 | Hit |
| Iron pike | 4.8 | 5 | 1000 → 992 | Hit |
| Iron pike | 7.5 | 5 | 1000 → 1000 | Rejected by reach/aim check |

Distances refer to target centers. Native melee eligibility uses the eye-to-hitbox intersection; these measurements are not interchangeable with weapon reach values.

| Projectile equipment | Distance | Charge ticks | Pitch offset from direct center aim | Server target health | Outcome |
|---|---:|---:|---:|---|---|
| Vanilla bow, vanilla arrow | 12 | 25 | -1° | 1000 → 991 | Hit; one arrow consumed |
| Vanilla bow, vanilla arrow | 32 | 25 | -6° | 1000 → 990 | Hit; one arrow consumed |
| Iron longbow, vanilla arrow | 12 | 30 | -1° | 1000 → 989 | Hit |
| Iron longbow, vanilla arrow | 32 | 30 | -6° | 1000 → 1000 | Miss |
| Iron longbow, vanilla arrow | 32 | 30 | -3.5° | 1000 → 992 | Hit |
| Iron longbow, vanilla arrow | 64 | 30 | -12° | 1000 → 1000 | Miss |
| Iron longbow, vanilla arrow | 64 | 30 | -9° | 1000 → 992 | Hit |
| Iron longbow, vanilla arrow | 64 | 30 | -7° | 1000 → 1000 | Miss |
| Iron throwing knife | 8 | 10 | -4° | 1000 → 993 | Hit; offhand arrows unchanged |
| Iron javelin | 12 | 15 | -4° | 1000 → 985 | Hit; offhand arrows unchanged |
| Iron crossbow, standard bolt | 12 | 22 loading, 1 firing | -2° | 1000 → 987 | Loaded NBT changed 0→1→0; one bolt consumed |

These are individual measurements, not estimates of maximum range, consistent accuracy or fixed damage. Native random critical hits, inaccuracy, armor and environmental effects can change results. A different ammunition, enchantment, equipment or buff combination requires its own evidence.

| Other test | Observation | Verified scope |
|---|---|---|
| Drink swiftness potion | Active main hand during drinking; speed effect appeared afterward | Consumption and synchronized potion effect |
| Forward splash swiftness potion | Item consumed, no self speed effect at observation time | Throwing does not imply a self buff |
| Downward splash swiftness potion | Item consumed, speed effect appeared | Self-targeted splash use |
| Diamond armor set | Integrated-server armor increased 0→20 | Equipping and armor value; damage reduction not measured |
| Shield | Active main hand during bounded use; released afterward | Blocking state; incoming-damage block not measured |
| FirstAid priority | Freezing reduced head to approximately 1.19/6 while vanilla health remained approximately 9.93/20 | Cover stopped despite a live target; explicit safe retreat dispatched |
| Healthy cover | After healing, controller chose a native pike attack; server target health 1000→988 | Controller-to-native-attack path |

The final build was restarted and smoke-tested. Baubles slots were available, test capability sources correctly reported `integrated_server`, and effective combat attributes were readable. `CombatController.useEquipment` successfully drank a potion and loaded an iron crossbow; its profile exposed `loaded:true` and bolt speed 1.6. A fresh 64-block longbow trial at -9° missed; a -8.5° trial hit. The resulting context-bound calibration then drove a controller shot at a new 64-block target, with server health 1000→990. These repeated measurements reinforce that a successful sample does not imply deterministic accuracy. Observations are retained in `combat-final-smoke-results.json`.

## Implementation and limits

The bridge reads ReachFix reach and uses RLCombat's native aiming and attack packet path. It exposes body health, item profiles, effects, bounding boxes and a wider, bounded entity observation area. The controller prioritizes critical FirstAid health, waits for attack cooldown, requires verified movement routes, and limits projectile fire to calibrated stationary targets with externally confirmed clearance. Crossbows, throwable weapons, shields and potions are distinguished by item classes and use stages.

The new controller is an explicit Dregora backend component. It is not yet wired into the existing Mineflayer agent loop, GUI or DeepSeek planner. Automatic pathfinding, ballistic obstacle checks, leading moving targets, multipart dragon targeting, dual wielding and continuous nunchaku attacks remain unfinished. Arbitrary special-item effects, armor damage reduction, shield mitigation and accessories have not been exhaustively tested. Every registered weapon, armor piece and special item has not been effect-tested.

Raw catalog and observations are retained locally under the ignored `build/runtime-test/` directory: `combat-catalog.json`, `combat-melee-neutral-results.json`, `combat-ranged-results.json`, `combat-longbow-calibration-results.json`, `combat-items-results.json`, `combat-crossbow-controller-results.json`, and `combat-splash-cover-results.json`. Tokens and launcher identity data are excluded from tracked artifacts.

Java build and four validation tests passed. Nineteen Node adapter/controller tests passed, covering long reach, critical body health, cooldown, allies, calibration invalidation, equipment modes, crossbow stages, bounded retreat and world-session changes.

After testing, tagged targets were removed. The test character's original inventory, skills, position and view were restored, temporary potion effects were cleared, and its body health was healed. The arena remains in the isolated test world for later measurements. The client was saved and closed, and temporary graphics settings were restored.
