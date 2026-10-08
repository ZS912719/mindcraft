import { randomUUID } from 'node:crypto';
import { DregoraAdapter } from './client.js';
import { BridgeError } from './protocol.js';

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function validateNpcOrder(uuid, command, destination) {
    if (typeof uuid !== 'string' || !uuidPattern.test(uuid)) throw new BridgeError('invalid_npc_uuid');
    if (!['follow', 'hold', 'retreat', 'summon', 'navigate'].includes(command)) throw new BridgeError('unsupported_npc_command');
    if (command === 'retreat' || command === 'navigate') {
        if (!destination || typeof destination !== 'object' || Array.isArray(destination)
            || Object.keys(destination).length !== 3 || !['x', 'y', 'z'].every(axis =>
                typeof destination[axis] === 'number' && Number.isFinite(destination[axis]) && Math.abs(destination[axis]) <= 30000000))
            throw new BridgeError('invalid_destination');
    } else if (destination !== undefined) throw new BridgeError('unexpected_destination');
    return { uuid, command, ...(destination === undefined ? {} : { destination: { ...destination } }) };
}

// Reuse authenticated loopback transport without invoking the player action API.
export class DregoraNpcAdapter {
    constructor(options = {}) {
        this.transport = new DregoraAdapter(options);
    }

    async getState() {
        const state = await this.transport.request('/v1/npcs');
        if (!state || state.protocol !== 1 || state.backend !== 'npc_prototype'
            || state.source !== 'integrated_server' || !uuidPattern.test(state.session)
            || !Number.isFinite(state.timestamp) || !Number.isInteger(state.dimension) || state.loadedOnly !== true
            || !Array.isArray(state.npcs) || !state.npcs.every(npc => npc && uuidPattern.test(npc.uuid)
                && typeof npc.alive === 'boolean' && Number.isFinite(npc.health) && typeof npc.movement === 'string'
                && ['follow', 'hold', 'retreat', 'summon', 'navigate'].includes(npc.command)
                && npc.position && ['x', 'y', 'z'].every(axis => Number.isFinite(npc.position[axis]))))
            throw new BridgeError('invalid_npc_state');
        if (Math.abs(Date.now() - state.timestamp) > this.transport.maxStateAgeMs) throw new BridgeError('stale_state');
        return state;
    }

    async command(uuid, command, { destination, id = randomUUID(), session } = {}) {
        const order = validateNpcOrder(uuid, command, destination);
        if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) throw new BridgeError('invalid_action_id');
        const state = await this.getState();
        if (session !== undefined && session !== state.session) throw new BridgeError('stale_npc_session');
        const npc = state.npcs.find(entity => entity.uuid.toLowerCase() === uuid.toLowerCase());
        if (!npc?.alive) throw new BridgeError('npc_not_loaded_or_alive');
        try {
            const result = await this.transport.request('/v1/npcs/commands', { id, session: state.session, ...order });
            if (!result || result.id !== id || typeof result.uuid !== 'string' || result.uuid.toLowerCase() !== uuid.toLowerCase() || result.session !== state.session
                || result.command !== command || result.status !== 'completed' || result.reason !== 'command_set'
                || result.effectVerified !== false) throw new BridgeError('invalid_npc_result');
            return result;
        } catch (error) {
            // Preserve the identity so a transport timeout can be recovered without a new command.
            error.details = { ...error.details, actionId: id, session: state.session, uuid };
            throw error;
        }
    }
}
