import { BridgeError, capabilityValue, validateState } from './protocol.js';

export function itemKey(item) {
    // Damage values are durability for damageable items, not item variants.
    return JSON.stringify([item?.id, item?.maxDamage > 0 ? 0 : item?.metadata ?? 0, item?.nbt ?? null]);
}

export function equipmentKey(state) {
    return JSON.stringify([['head', 'chest', 'legs', 'feet'].map(slot => itemKey(state.equipment?.[slot])),
        capabilityValue(state, 'baubles')?.slots?.map(itemKey) ?? null]);
}

export function aimAt(state, target, pitchOffset = 0) {
    const b = target.bounds;
    const x = b ? (b.minX + b.maxX) / 2 : target.x;
    const y = b ? (b.minY + b.maxY) / 2 : target.y;
    const z = b ? (b.minZ + b.maxZ) / 2 : target.z;
    const dx = x - state.position.x, dz = z - state.position.z;
    const dy = y - state.position.y - state.eyeHeight;
    return { yaw: -Math.atan2(dx, dz) * 180 / Math.PI,
        pitch: Math.max(-90, Math.min(90, -Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI + pitchOffset)) };
}

export function distanceToBounds(state, target) {
    if (!target.bounds) return Infinity;
    const p = state.position, b = target.bounds;
    return Math.hypot(Math.max(b.minX - p.x, 0, p.x - b.maxX),
        Math.max(b.minY - p.y - state.eyeHeight, 0, p.y + state.eyeHeight - b.maxY),
        Math.max(b.minZ - p.z, 0, p.z - b.maxZ));
}

function criticalHealth(state) {
    const body = capabilityValue(state, 'body');
    return state.health <= state.maxHealth * 0.3 || (body && Object.values(body)
        .some(part => part.critical && part.health <= part.maxHealth * 0.35));
}

function calibration(state, target, profile, ammo) {
    if (!profile || profile.weaponKey !== itemKey(state.equipment?.mainhand)
        || profile.ammoKey !== itemKey(ammo) || profile.dimension !== state.dimension
        || profile.equipmentKey !== equipmentKey(state)
        || profile.skillsKey !== JSON.stringify(capabilityValue(state, 'skills'))
        || profile.effectsKey !== JSON.stringify((state.effects ?? []).map(e => [e.id, e.amplifier]))
        || !Number.isInteger(profile.chargeTicks) || profile.chargeTicks < 1 || profile.chargeTicks > 100
        || !Array.isArray(profile.samples) || profile.samples.length < 1) return null;
    // Only successful, stationary-target measurements qualify as firing calibration.
    const samples = profile.samples.filter(s => s.hitVerified === true && Number.isFinite(s.distance)
        && s.distance > 0 && Number.isFinite(s.pitchOffset) && Math.abs(s.pitchOffset) <= 90)
        .sort((a, b) => a.distance - b.distance);
    const distance = target.distance;
    if (!samples.length || distance < samples[0].distance - 0.5 || distance > samples.at(-1).distance + 0.5) return null;
    if (target.velocity && Math.hypot(target.velocity.x, target.velocity.y, target.velocity.z) > 0.03) return null;
    const upper = samples.find(s => s.distance >= distance) ?? samples.at(-1);
    const lower = samples.findLast(s => s.distance <= distance) ?? samples[0];
    const fraction = upper.distance === lower.distance ? 0 : (distance - lower.distance) / (upper.distance - lower.distance);
    return { ticks: profile.chargeTicks, pitchOffset: lower.pitchOffset + fraction * (upper.pitchOffset - lower.pitchOffset) };
}

export function decideCombat(state, { intent = 'cover', targetUuid, allies = [], rangedProfile, ammo,
    clearShotVerified = false, safeRetreatVerified = false } = {}) {
    validateState(state);
    if (!state.connected || !state.alive || state.guiOpen) return { type: 'stop', args: {}, reason: 'not_controllable' };
    if (!['cover', 'defend', 'retreat'].includes(intent)) throw new BridgeError('invalid_combat_intent');
    const target = state.entities.find(e => e.uuid === targetUuid && Number.isFinite(e.health) && e.health > 0);
    if (intent === 'retreat' || criticalHealth(state)) {
        if (!safeRetreatVerified || !target) return { type: 'stop', args: {}, reason: 'retreat_route_required' };
        return { type: 'retreat', look: aimAt(state, target), args: { back: true, ticks: 5 }, reason: 'retreat' };
    }
    if (!target || allies.includes(target.uuid) || !target.visible) return { type: 'stop', args: {}, reason: 'no_valid_target' };
    if (state.combat?.activeHand) return { type: 'wait', reason: 'item_use_active' };
    const weapon = state.equipment?.mainhand;
    const kind = weapon?.profile?.kind;
    // Unknown, consumable and special-use items must not silently become melee weapons.
    if (kind === 'bow' || kind === 'throwable' || kind === 'crossbow') {
        if (state.mods?.baubles?.status === 'unknown')
            return { type: 'stop', args: {}, reason: 'bauble_effects_unknown' };
        if (kind !== 'throwable' && itemKey(ammo) !== itemKey(state.equipment?.offhand))
            return { type: 'stop', args: {}, reason: 'verified_offhand_ammunition_required' };
        if (kind === 'crossbow' && weapon.profile.loaded !== true)
            return { type: 'stop', args: {}, reason: 'crossbow_loading_required' };
        const shot = calibration(state, target, rangedProfile, kind === 'throwable' ? weapon : ammo);
        if (!shot || !clearShotVerified) return { type: 'stop', args: {}, reason: 'ranged_calibration_or_clearance_required' };
        return { type: 'shot', look: aimAt(state, target, shot.pitchOffset),
            args: { hand: 'main', ticks: kind === 'crossbow' ? 1 : shot.ticks }, targetUuid, reason: 'calibrated_shot' };
    }
    if (kind !== 'melee') return { type: 'stop', args: {}, reason: 'special_item_profile_required' };
    if (state.combat?.status !== 'available') return { type: 'stop', args: {}, reason: 'reach_unknown' };
    if (distanceToBounds(state, target) >= state.combat.mainReach - 0.1)
        return { type: 'stop', args: {}, reason: 'approach_route_required' };
    if (state.combat.attackStrength < 1) return { type: 'wait', reason: 'attack_cooldown' };
    return { type: 'attack', look: aimAt(state, target), args: { entityId: target.entityId, uuid: target.uuid }, reason: 'melee_ready' };
}

export class CombatController {
    constructor(adapter) { this.adapter = adapter; }

    async useEquipment(mode, { ticks } = {}) {
        const state = await this.adapter.getState();
        const weapon = state.equipment?.mainhand, profile = weapon?.profile;
        const session = state.session;
        let duration;
        if (mode === 'block' && profile?.kind === 'shield') duration = ticks ?? 20;
        else if (mode === 'drink' && profile?.kind === 'potion' && profile.useAction === 'DRINK') duration = profile.maxUseTicks + 5;
        else if (mode === 'self_splash' && profile?.class === 'net.minecraft.item.ItemSplashPotion') {
            const result = await this.adapter.execute('look', { yaw: state.position.yaw, pitch: 90 }, { session });
            if (result.status !== 'completed') return result;
            duration = 1;
        } else if (mode === 'load_crossbow' && profile?.kind === 'crossbow' && profile.loaded === false) {
            duration = profile.maxUseTicks;
        } else throw new BridgeError('unsupported_equipment_mode');
        if (!Number.isInteger(duration) || duration < 1 || duration > 100) throw new BridgeError('invalid_equipment_duration');
        return this.adapter.execute('use_item', { hand: 'main', ticks: duration }, { session });
    }

    async step(options = {}) {
        const state = await this.adapter.getState();
        const decision = decideCombat(state, options);
        if (decision.type === 'wait') return { decision };
        const session = state.session;
        if (decision.look) {
            const aimed = await this.adapter.execute('look', decision.look, { session });
            if (aimed.status !== 'completed') return { decision, result: aimed };
            const fresh = await this.adapter.getState();
            if (fresh.session !== session || (decision.type !== 'retreat' && criticalHealth(fresh))) {
                await this.adapter.stop();
                throw new BridgeError('combat_state_changed');
            }
            if (decision.type === 'shot') {
                const next = decideCombat(fresh, options);
                if (next.type !== 'shot' || next.targetUuid !== decision.targetUuid) {
                    await this.adapter.stop();
                    throw new BridgeError('combat_state_changed');
                }
            }
        }
        const type = decision.type === 'retreat' ? 'move' : decision.type === 'shot' ? 'use_item' : decision.type;
        const result = await this.adapter.execute(type, decision.args, { session });
        // Preserve bridge observation evidence; item use does not establish projectile impact.
        return { decision, result, effectVerified: result.effectVerified === true };
    }
}
