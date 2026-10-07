import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { DregoraAdapter } from '../../src/adapters/dregora/client.js';
import { aimAt, itemKey, equipmentKey } from '../../src/adapters/dregora/combat.js';

export class CombatLab {
    constructor(adapter = new DregoraAdapter()) { this.adapter = adapter; }

    async serverState() { return this.adapter.request('/v1/test/state'); }

    async command(command) {
        const result = await this.adapter.execute('test_command', { command }, { timeoutMs: 10000 });
        if (result.status !== 'completed') throw new Error(`${command.split(' ')[0]}: ${result.reason}`);
        await delay(350);
        return result;
    }

    async catalog() {
        const rows = [];
        for (let offset = 0; ; offset += 50) {
            const page = await this.adapter.getCatalog('items', { offset, limit: 50 });
            rows.push(...page.entries);
            if (offset + 50 >= page.total) break;
        }
        return rows;
    }

    async equip(id, { metadata = 0, nbt = '', slot = 'slot.hotbar.0', count = 1 } = {}) {
        if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(id) || !Number.isInteger(metadata) || metadata < 0
            || !Number.isInteger(count) || count < 1 || count > 64 || !/^slot\.(hotbar\.[0-8]|armor\.(head|chest|legs|feet)|weapon\.offhand)$/.test(slot))
            throw new Error('invalid_equipment_fixture');
        const state = await this.adapter.getState();
        await this.command(`replaceitem entity ${state.player} ${slot} ${id} ${count} ${metadata}${nbt ? ` ${nbt}` : ''}`);
        if (slot === 'slot.hotbar.0') await this.adapter.execute('select_slot', { slot: 0 });
        await delay(800);
        return this.adapter.getState();
    }

    async target(distance) {
        if (!Number.isFinite(distance) || distance < 1 || distance > 90) throw new Error('invalid_fixture_distance');
        await this.serverState();
        const cleanup = await this.adapter.execute('test_command', { command: 'kill @e[tag=MindcraftFixture]' });
        if (cleanup.status !== 'completed' && cleanup.reason !== 'test_command_failed') throw new Error(cleanup.reason);
        await delay(350);
        const state = await this.adapter.getState();
        const name = `Mindcraft_${randomUUID().slice(0, 8)}`;
        const p = state.position;
        await this.command(`summon minecraft:pig ${p.x.toFixed(4)} ${p.y.toFixed(4)} ${(p.z + distance).toFixed(4)} {NoAI:1b,NoGravity:1b,Silent:1b,PersistenceRequired:1b,Tags:["MindcraftFixture"],CustomName:"${name}",Attributes:[{Name:"generic.maxHealth",Base:1000.0}],Health:1000.0f}`);
        const current = await this.adapter.getState();
        const target = current.entities.find(e => e.name === name);
        if (!target) throw new Error('fixture_not_observed');
        return target;
    }

    async measureMelee(id, distance, equipmentOptions = {}) {
        await this.equip(id, equipmentOptions);
        const target = await this.target(distance);
        await delay(1200);
        const before = await this.adapter.getState();
        const serverBefore = await this.serverState();
        await this.adapter.execute('look', aimAt(before, target));
        await delay(350);
        const result = await this.adapter.execute('attack', { entityId: target.entityId, uuid: target.uuid });
        await delay(1000);
        const after = await this.adapter.getState();
        const serverAfter = await this.serverState();
        const health = s => s.entities.find(e => e.uuid === target.uuid)?.health;
        return { scenario: 'melee', id, distance, weapon: before.equipment.mainhand,
            reach: before.combat, target, result, serverHealthBefore: health(serverBefore),
            serverHealthAfter: health(serverAfter), hitVerified: health(serverAfter) < health(serverBefore),
            clientHealthAfter: after.entities.find(e => e.uuid === target.uuid)?.health };
    }

    async measureShot(id, distance, { ammo = 'minecraft:arrow', chargeTicks = 40, pitchOffset = 0, ...equipmentOptions } = {}) {
        await this.equip(ammo, { slot: 'slot.weapon.offhand', count: 64 });
        await this.equip(id, equipmentOptions);
        const target = await this.target(distance);
        const before = await this.adapter.getState(), serverBefore = await this.serverState();
        await this.adapter.execute('look', aimAt(before, target, pitchOffset));
        await delay(350);
        const result = await this.adapter.execute('use_item', { hand: 'main', ticks: chargeTicks });
        await delay(chargeTicks * 50 + 4500);
        await this.adapter.stop();
        const after = await this.adapter.getState(), serverAfter = await this.serverState();
        const health = s => s.entities.find(e => e.uuid === target.uuid)?.health;
        return { scenario: 'ranged', id, ammo, distance, chargeTicks, pitchOffset,
            weaponKey: itemKey(before.equipment.mainhand), ammoKey: itemKey(before.equipment.mainhand.profile.kind === 'throwable'
                ? before.equipment.mainhand : before.equipment.offhand),
            equipmentKey: equipmentKey(before),
            weapon: before.equipment.mainhand, ammoBefore: before.equipment.offhand, ammoAfter: after.equipment.offhand,
            target, result, serverHealthBefore: health(serverBefore), serverHealthAfter: health(serverAfter),
            hitVerified: health(serverAfter) < health(serverBefore),
            dimension: before.dimension, skillsKey: JSON.stringify(before.mods.skills?.value ?? null),
            effectsKey: JSON.stringify(before.effects.map(e => [e.id, e.amplifier])) };
    }

    async measureUse(id, { ticks = 40, ...equipmentOptions } = {}) {
        const before = await this.equip(id, equipmentOptions);
        const serverBefore = await this.serverState();
        const result = await this.adapter.execute('use_item', { hand: 'main', ticks });
        await delay(Math.max(350, ticks * 25));
        const during = await this.adapter.getState();
        await delay(ticks * 25 + 1000);
        await this.adapter.stop();
        return { scenario: 'use', id, result, before, during, after: await this.adapter.getState(),
            serverBefore, serverAfter: await this.serverState() };
    }
}
