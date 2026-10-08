export const PROTOCOL_VERSION = 1;
export const ACTIONS = Object.freeze({
    stop: {},
    move: { ticks: 'ticks20', forward: 'boolean?', back: 'boolean?', left: 'boolean?', right: 'boolean?', jump: 'boolean?', sneak: 'boolean?', sprint: 'boolean?' },
    look: { yaw: 'yaw', pitch: 'pitch' },
    select_slot: { slot: 'slot' },
    attack: { entityId: 'entityId', uuid: 'uuid' },
    use_item: { hand: 'hand', ticks: 'ticks100' },
    interact_block: {},
    equip_armor: { slot: 'inventorySlot' },
    test_command: { command: 'testCommand' },
    test_lock: { slot: 'inventorySlot', requirements: 'requirements' },
    test_advancement: { granted: 'boolean' },
});

export class BridgeError extends Error {
    constructor(code, details = {}) {
        super(code);
        this.name = 'BridgeError';
        this.code = code;
        this.details = details;
    }
}

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function validateAction(type, args = {}) {
    const schema = Object.hasOwn(ACTIONS, type) ? ACTIONS[type] : null;
    if (!schema || !args || Array.isArray(args) || typeof args !== 'object') throw new BridgeError('invalid_action');
    for (const key of Object.keys(args)) if (!Object.hasOwn(schema, key)) throw new BridgeError('unknown_argument', { key });
    for (const [key, rule] of Object.entries(schema)) {
        const value = args[key];
        if (rule.endsWith('?') && value === undefined) continue;
        let valid;
        if (rule === 'boolean?' || rule === 'boolean') valid = typeof value === 'boolean';
        else if (rule === 'hand') valid = value === 'main' || value === 'off';
        else if (rule === 'uuid') valid = typeof value === 'string' && uuidPattern.test(value);
        else if (rule === 'requirements') valid = Array.isArray(value) && value.length >= 1 && value.length <= 8
            && value.every(expression => typeof expression === 'string' && expression.length <= 256);
        else if (rule === 'testCommand') valid = typeof value === 'string' && value.length <= 2048
            && !/[\r\n]/.test(value) && /^(give|replaceitem|summon|effect|tp|kill|gamemode|time|weather|difficulty|gamerule|reskillable|fill|advancement|mindcraft_npc) .+$/.test(value);
        else {
            const bounds = { ticks20: [1, 20], ticks100: [1, 100], yaw: [-360, 360], pitch: [-90, 90], slot: [0, 8], inventorySlot: [0, 35], entityId: [0, 2147483647] }[rule];
            valid = typeof value === 'number' && Number.isFinite(value) && value >= bounds[0] && value <= bounds[1];
            if (rule !== 'yaw' && rule !== 'pitch') valid &&= Number.isInteger(value);
        }
        if (!valid) throw new BridgeError('invalid_argument', { key });
    }
    return { ...args };
}

export function validateState(state, now = Date.now(), maxAgeMs = 1500) {
    if (!state || state.protocol !== PROTOCOL_VERSION || typeof state.connected !== 'boolean'
        || !Number.isFinite(state.timestamp)) throw new BridgeError('invalid_state');
    if (now - state.timestamp > maxAgeMs || state.timestamp - now > maxAgeMs) throw new BridgeError('stale_state');
    if (state.connected && (typeof state.session !== 'string' || !uuidPattern.test(state.session)
        || !Number.isInteger(state.dimension) || typeof state.alive !== 'boolean'
        || !state.position || !['x', 'y', 'z', 'yaw', 'pitch'].every(key => Number.isFinite(state.position[key]))
        || !Array.isArray(state.inventory) || !Array.isArray(state.entities))) throw new BridgeError('invalid_state');
    return state;
}

export function capabilityValue(state, key) {
    const capability = state.mods?.[key];
    return capability?.status === 'available' ? capability.value : null;
}
