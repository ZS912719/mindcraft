import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { BridgeError, validateAction, validateState } from './protocol.js';

export class DregoraAdapter {
    constructor({ url = 'http://127.0.0.1:9091', token = process.env.MINDCRAFT_BRIDGE_TOKEN,
        requestTimeoutMs = 2500, maxStateAgeMs = 1500 } = {}) {
        const endpoint = new URL(url);
        if (endpoint.protocol !== 'http:' || endpoint.hostname !== '127.0.0.1' || endpoint.username
            || endpoint.password || endpoint.pathname !== '/' || endpoint.search || endpoint.hash)
            throw new BridgeError('loopback_url_required');
        if (typeof token !== 'string' || token.length < 32) throw new BridgeError('bridge_token_required');
        if (!Number.isFinite(requestTimeoutMs) || requestTimeoutMs < 100 || requestTimeoutMs > 10000
            || !Number.isFinite(maxStateAgeMs) || maxStateAgeMs < 100 || maxStateAgeMs > 5000)
            throw new BridgeError('invalid_timeout');
        this.url = endpoint.origin;
        this.token = token;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxStateAgeMs = maxStateAgeMs;
    }

    async request(path, body) {
        let response;
        try {
            response = await fetch(`${this.url}${path}`, {
                method: body ? 'POST' : 'GET',
                headers: { Authorization: `Bearer ${this.token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
                body: body ? JSON.stringify(body) : undefined,
                redirect: 'error',
                signal: AbortSignal.timeout(this.requestTimeoutMs),
            });
            const data = await response.json();
            if (!response.ok) throw new BridgeError(data.error || 'http_error', { status: response.status });
            return data;
        } catch (error) {
            if (error instanceof BridgeError) throw error;
            throw new BridgeError('bridge_unreachable', { cause: error.name });
        }
    }

    async getState() {
        return validateState(await this.request('/v1/state'), Date.now(), this.maxStateAgeMs);
    }

    async getCatalog(kind, { offset = 0, limit = 100 } = {}) {
        if (!['items', 'blocks', 'recipes'].includes(kind) || !Number.isInteger(offset) || offset < 0 || offset > 100000
            || !Number.isInteger(limit) || limit < 1 || limit > 100) throw new BridgeError('invalid_catalog_page');
        return this.request(`/v1/catalog?kind=${kind}&offset=${offset}&limit=${limit}`);
    }

    async submit(type, args = {}, { id = randomUUID(), session } = {}) {
        const validated = validateAction(type, args);
        if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) throw new BridgeError('invalid_action_id');
        const state = await this.getState();
        if (!state.connected) throw new BridgeError('not_connected');
        if (session !== undefined && session !== state.session) throw new BridgeError('stale_session');
        if (type !== 'stop' && (!state.alive || state.guiOpen)) throw new BridgeError('game_not_controllable');
        try {
            const result = await this.request('/v1/actions', { id, session: state.session, type, args: validated });
            return this.validateResult(result, id);
        } catch (error) {
            // A transport failure does not prove that the game rejected the action.
            error.details = { ...error.details, actionId: id, session: state.session };
            throw error;
        }
    }

    validateResult(result, id) {
        if (!result || result.id !== id || !['pending', 'completed', 'rejected'].includes(result.status)
            || typeof result.reason !== 'string' || result.effectVerified !== false) throw new BridgeError('invalid_action_result');
        return result;
    }

    async getAction(id) {
        if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) throw new BridgeError('invalid_action_id');
        return this.validateResult(await this.request(`/v1/actions/${id}`), id);
    }

    async execute(type, args = {}, options = {}) {
        const timeoutMs = options.timeoutMs ?? 3500;
        if (!Number.isFinite(timeoutMs) || timeoutMs < 100 || timeoutMs > 30000) throw new BridgeError('invalid_timeout');
        let result = await this.submit(type, args, options);
        const deadline = Date.now() + timeoutMs;
        while (result.status === 'pending') {
            if (Date.now() >= deadline) throw new BridgeError('action_result_timeout', { actionId: result.id });
            await delay(50);
            try {
                result = await this.getAction(result.id);
            } catch (error) {
                error.details = { ...error.details, actionId: result.id };
                throw error;
            }
        }
        return result;
    }

    async stop() { return this.execute('stop'); }
}
