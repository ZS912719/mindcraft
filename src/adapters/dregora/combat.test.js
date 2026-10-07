import test from 'node:test';
import assert from 'node:assert/strict';
import { decideCombat, itemKey, equipmentKey, CombatController } from './combat.js';

const uuid = '00000000-0000-4000-8000-000000000001';
function state() {
    return { protocol: 1, timestamp: Date.now(), connected: true, session: uuid, dimension: 0,
        alive: true, health: 20, maxHealth: 20, guiOpen: false, eyeHeight: 1.62,
        position: { x: 0, y: 0, z: 0, yaw: 0, pitch: 0 }, inventory: [], effects: [],
        combat: { status: 'available', mainReach: 5, attackStrength: 1 },
        equipment: { mainhand: { id: 'test:pike', metadata: 0, profile: { kind: 'melee' } } },
        entities: [{ uuid, entityId: 1, health: 20, visible: true, x: 0, y: 0, z: 4,
            distance: 4, bounds: { minX: -0.3, maxX: 0.3, minY: 0, maxY: 2, minZ: 3.7, maxZ: 4.3 } }] };
}

test('long weapons can attack beyond three blocks, shorter weapons cannot', () => {
    const s = state();
    assert.equal(decideCombat(s, { targetUuid: uuid }).type, 'attack');
    s.combat.mainReach = 3;
    assert.equal(decideCombat(s, { targetUuid: uuid }).reason, 'approach_route_required');
});

test('critical FirstAid body health overrides healthy vanilla health', () => {
    const s = state();
    s.mods = { body: { status: 'available', value: { head: { critical: true, health: 1, maxHealth: 6 } } } };
    assert.equal(decideCombat(s, { targetUuid: uuid, safeRetreatVerified: true }).type, 'retreat');
    assert.equal(decideCombat(s, { targetUuid: uuid }).type, 'stop');
});

test('unknown reach, special items, allies and cooldown prevent attacks', () => {
    const s = state();
    assert.equal(decideCombat(s, { targetUuid: uuid, allies: [uuid] }).type, 'stop');
    s.combat.attackStrength = 0.5;
    assert.equal(decideCombat(s, { targetUuid: uuid }).type, 'wait');
    s.combat.attackStrength = 1; s.combat.status = 'unknown';
    assert.equal(decideCombat(s, { targetUuid: uuid }).reason, 'reach_unknown');
    s.equipment.mainhand.profile.kind = 'potion';
    assert.equal(decideCombat(s, { targetUuid: uuid }).reason, 'special_item_profile_required');
});

test('ranged measurements are tied to weapon, ammunition, buffs, skills and tested distances', () => {
    const s = state(); s.equipment.mainhand.profile.kind = 'bow';
    const ammo = { id: 'test:arrow' };
    s.equipment.offhand = ammo;
    const profile = { weaponKey: itemKey(s.equipment.mainhand), ammoKey: itemKey(ammo), dimension: 0,
        equipmentKey: equipmentKey(s), skillsKey: 'null', effectsKey: '[]', chargeTicks: 30,
        samples: [{ distance: 4, pitchOffset: -2, hitVerified: true }] };
    const options = { targetUuid: uuid, rangedProfile: profile, ammo, clearShotVerified: true };
    assert.equal(decideCombat(s, options).type, 'shot');
    assert.equal(decideCombat(s, { ...options, clearShotVerified: false }).type, 'stop');
    assert.equal(decideCombat(s, { ...options, ammo: { id: 'test:heavy_arrow' } }).type, 'stop');
    s.equipment.head = { id: 'test:helmet' };
    assert.equal(decideCombat(s, options).type, 'stop');
    delete s.equipment.head;
    s.entities[0].distance = 40;
    assert.equal(decideCombat(s, options).type, 'stop');
    s.entities[0].distance = 4; s.effects = [{ id: 'test:buff', amplifier: 1 }];
    assert.equal(decideCombat(s, options).type, 'stop');
});

test('ordinary wear does not change a weapon variant, but enchantment NBT does', () => {
    const bow = { id: 'minecraft:bow', maxDamage: 384, metadata: 0 };
    assert.equal(itemKey(bow), itemKey({ ...bow, metadata: 50 }));
    assert.notEqual(itemKey(bow), itemKey({ ...bow, nbt: '{ench:[]}' }));
});

test('crossbows require loaded state and use one firing tick after calibration', () => {
    const s = state(); s.equipment.mainhand.profile = { kind: 'crossbow', loaded: false };
    const ammo = { id: 'test:bolt' }; s.equipment.offhand = ammo;
    const options = { targetUuid: uuid, ammo, clearShotVerified: true };
    assert.equal(decideCombat(s, options).reason, 'crossbow_loading_required');
    s.equipment.mainhand.profile.loaded = true;
    options.rangedProfile = { weaponKey: itemKey(s.equipment.mainhand), ammoKey: itemKey(ammo), dimension: 0,
        equipmentKey: equipmentKey(s), skillsKey: 'null', effectsKey: '[]', chargeTicks: 22,
        samples: [{ distance: 4, pitchOffset: -2, hitVerified: true }] };
    assert.equal(decideCombat(s, options).args.ticks, 1);
});

test('support item modes distinguish drinking, downward splashing, blocking and loading', async () => {
    const s = state(), calls = [];
    const controller = new CombatController({ getState: async () => s,
        execute: async (type, args) => { calls.push({ type, args }); return { status: 'completed' }; } });
    s.equipment.mainhand.profile = { kind: 'potion', useAction: 'DRINK', maxUseTicks: 32 };
    await controller.useEquipment('drink');
    assert.equal(calls.at(-1).args.ticks, 37);
    s.equipment.mainhand.profile = { kind: 'potion', class: 'net.minecraft.item.ItemSplashPotion' };
    await controller.useEquipment('self_splash');
    assert.equal(calls.at(-2).args.pitch, 90); assert.equal(calls.at(-1).args.ticks, 1);
    s.equipment.mainhand.profile = { kind: 'crossbow', loaded: false, maxUseTicks: 22 };
    await controller.useEquipment('load_crossbow');
    assert.equal(calls.at(-1).args.ticks, 22);
    await assert.rejects(controller.useEquipment('drink'), { code: 'unsupported_equipment_mode' });
    s.equipment.mainhand.profile = { kind: 'shield' };
    await assert.rejects(controller.useEquipment('block', { ticks: 101 }), { code: 'invalid_equipment_duration' });
});

test('controller permits an emergency retreat and preserves bounded movement', async () => {
    const s = state(); s.health = 1;
    const calls = [];
    const controller = new CombatController({ getState: async () => s,
        execute: async (type, args) => { calls.push({ type, args }); return { status: 'completed' }; } });
    await controller.step({ intent: 'retreat', targetUuid: uuid, safeRetreatVerified: true });
    assert.deepEqual(calls.map(c => c.type), ['look', 'move']);
    assert.equal(calls[1].args.ticks, 5);
});

test('controller stops after a session change between aiming and attacking', async () => {
    const s = state(); let count = 0, stopped = false, attacked = false;
    const controller = new CombatController({ getState: async () => ++count === 1 ? s : { ...s, session: 'changed' },
        execute: async type => { attacked ||= type === 'attack'; return { status: 'completed' }; },
        stop: async () => { stopped = true; } });
    await assert.rejects(controller.step({ targetUuid: uuid }), { code: 'combat_state_changed' });
    assert.equal(stopped, true); assert.equal(attacked, false);
});
