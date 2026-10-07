import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { DregoraAdapter } from './client.js';
import { capabilityValue, validateAction, validateState } from './protocol.js';

const token = 'test-only-token-with-at-least-32-characters';
const session = '00000000-0000-4000-8000-000000000001';
const state = () => ({ protocol: 1, timestamp: Date.now(), connected: true, session,
    dimension: 0, alive: true, guiOpen: false, position: { x: 1, y: 64, z: 1, yaw: 0, pitch: 0 }, inventory: [], entities: [] });

async function fixture(t, handler) {
    const server = createServer(async (request, response) => {
        try {
            assert.equal(request.headers.authorization, `Bearer ${token}`);
            await handler(request, response);
        } catch (error) {
            response.writeHead(500);
            response.end(JSON.stringify({ error: error.message }));
        }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
    return new DregoraAdapter({ url: `http://127.0.0.1:${server.address().port}`, token });
}

function reply(response, data, status = 200) {
    response.writeHead(status, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify(data));
}

test('rejects unbounded controls, arbitrary fields, malformed targets and nonfinite angles', () => {
    for (const [type, args] of [
        ['move', { ticks: 21 }], ['move', { ticks: 1.5 }], ['move', { ticks: 5, forward: 'true' }],
        ['stop', { script: 'anything' }], ['look', { yaw: NaN, pitch: 0 }], ['look', { yaw: 0, pitch: 91 }],
        ['select_slot', { slot: 9 }], ['attack', { entityId: 2, uuid: 'invalid' }],
        ['use_item', { hand: 'main', ticks: 101 }], ['__proto__', {}],
    ]) assert.throws(() => validateAction(type, args));
    assert.deepEqual(validateAction('move', { ticks: 20, forward: true }), { ticks: 20, forward: true });
    assert.deepEqual(validateAction('stop'), {});
});

test('unknown capability values stay unknown and stale snapshots are rejected', () => {
    assert.equal(capabilityValue({ mods: { thirst: { status: 'unknown', value: { level: 20 } } } }, 'thirst'), null);
    assert.deepEqual(capabilityValue({ mods: { thirst: { status: 'available', value: { level: 3 } } } }, 'thirst'), { level: 3 });
    assert.throws(() => validateState({ ...state(), timestamp: Date.now() - 5000 }), { code: 'stale_state' });
    assert.throws(() => validateState({ ...state(), position: { x: NaN } }), { code: 'invalid_state' });
});

test('requires a token and refuses remote endpoints or embedded credentials', () => {
    assert.throws(() => new DregoraAdapter({ token: 'short' }), { code: 'bridge_token_required' });
    for (const url of ['http://example.org', 'https://127.0.0.1', 'http://user@127.0.0.1', 'http://127.0.0.1/path'])
        assert.throws(() => new DregoraAdapter({ token, url }), { code: 'loopback_url_required' });
});

test('submits a session-bound action and polls without claiming a verified effect', async t => {
    let submitted;
    let polls = 0;
    const adapter = await fixture(t, async (request, response) => {
        if (request.url === '/v1/state') return reply(response, state());
        if (request.method === 'POST') {
            let body = '';
            for await (const part of request) body += part;
            submitted = JSON.parse(body);
            return reply(response, { id: submitted.id, status: 'pending', reason: 'queued', effectVerified: false }, 202);
        }
        polls++;
        reply(response, { id: submitted.id, status: 'completed', reason: 'dispatched', effectVerified: false });
    });
    const result = await adapter.execute('move', { ticks: 5, forward: true });
    assert.equal(submitted.session, session);
    assert.equal(submitted.type, 'move');
    assert.equal(polls, 1);
    assert.equal(result.effectVerified, false);
});

test('rejects stale sessions and open menus before submitting actions', async t => {
    let posts = 0;
    let menu = false;
    const adapter = await fixture(t, (request, response) => {
        if (request.method === 'POST') posts++;
        reply(response, { ...state(), guiOpen: menu });
    });
    await assert.rejects(adapter.submit('stop', {}, { session: 'old' }), { code: 'stale_session' });
    menu = true;
    await assert.rejects(adapter.submit('move', { ticks: 5 }), { code: 'game_not_controllable' });
    assert.equal(posts, 0);
});

test('forwards rejection reasons and does not retry rejected actions', async t => {
    let posts = 0;
    const adapter = await fixture(t, (request, response) => {
        if (request.method === 'GET') return reply(response, state());
        posts++;
        reply(response, { error: 'queue_full' }, 429);
    });
    await assert.rejects(adapter.execute('stop'), error => error.code === 'queue_full' && !!error.details.actionId);
    assert.equal(posts, 1);
});

test('preserves the action id after an uncertain transport failure', async t => {
    let posts = 0;
    const adapter = await fixture(t, (request, response) => {
        if (request.method === 'GET') return reply(response, state());
        posts++;
        response.destroy();
    });
    await assert.rejects(adapter.submit('stop', {}, { id: 'recoverable-id' }), error =>
        error.code === 'bridge_unreachable' && error.details.actionId === 'recoverable-id');
    assert.equal(posts, 1);
});

test('does not forward bearer credentials through redirects', async t => {
    const adapter = await fixture(t, (_request, response) => {
        response.writeHead(302, { Location: 'http://example.org/' }); response.end();
    });
    await assert.rejects(adapter.getState(), { code: 'bridge_unreachable' });
});

test('retains recovery information when result polling disconnects', async t => {
    const adapter = await fixture(t, (request, response) => {
        if (request.url === '/v1/state') return reply(response, state());
        if (request.method === 'POST') return reply(response,
            { id: 'poll-recovery', status: 'pending', reason: 'queued', effectVerified: false }, 202);
        response.destroy();
    });
    await assert.rejects(adapter.execute('stop', {}, { id: 'poll-recovery' }), error =>
        error.code === 'bridge_unreachable' && error.details.actionId === 'poll-recovery');
});

test('queries bounded catalog pages and rejects invalid page sizes', async t => {
    const adapter = await fixture(t, (request, response) => {
        assert.equal(request.url, '/v1/catalog?kind=recipes&offset=5&limit=10');
        reply(response, { kind: 'recipes', total: 20, offset: 5, entries: [] });
    });
    assert.equal((await adapter.getCatalog('recipes', { offset: 5, limit: 10 })).total, 20);
    await assert.rejects(adapter.getCatalog('recipes', { limit: 1000 }), { code: 'invalid_catalog_page' });
});

test('retains native compound requirement failures without retrying', async t => {
    let posts = 0;
    const eligibility = { allowed: false, subjects: [{ role: 'item', missing: [{ type: 'ORRequirement',
        achieved: false, children: [{ type: 'SkillRequirement', skill: 'reskillable:magic', currentLevel: 1, requiredLevel: 8 },
            { type: 'AdvancementRequirement', advancement: 'minecraft:story/smelt_iron', achieved: false }] }] }] };
    const adapter = await fixture(t, async (request, response) => {
        if (request.method === 'GET') return reply(response, state());
        posts++;
        let body = '';
        for await (const part of request) body += part;
        reply(response, { id: JSON.parse(body).id, status: 'rejected', reason: 'requirements_not_met', effectVerified: false, eligibility });
    });
    const result = await adapter.execute('use_item', { hand: 'main', ticks: 40 });
    assert.deepEqual(result.eligibility, eligibility);
    assert.equal(posts, 1);
});

test('requires verification evidence for a confirmed action result', () => {
    const adapter = new DregoraAdapter({ token });
    const result = { id: 'evidence', status: 'completed', reason: 'dispatched', effectVerified: true };
    assert.throws(() => adapter.validateResult(result, 'evidence'), { code: 'invalid_action_result' });
    result.verification = { status: 'observed_change', effectVerified: true, source: 'integrated_server', changes: ['targetHealth'] };
    assert.equal(adapter.validateResult(result, 'evidence').effectVerified, true);
    assert.throws(() => adapter.validateResult({ ...result, status: 'pending' }, 'evidence'), { code: 'invalid_action_result' });
});

test('bounds survival armor slots and test-only runtime requirement fixtures', () => {
    assert.deepEqual(validateAction('equip_armor', { slot: 35 }), { slot: 35 });
    assert.throws(() => validateAction('equip_armor', { slot: 36 }));
    assert.throws(() => validateAction('test_lock', { slot: 0, requirements: [] }));
    assert.throws(() => validateAction('test_lock', { slot: 0, requirements: Array(9).fill('none') }));
});
