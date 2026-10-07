import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { DregoraNpcAdapter, validateNpcOrder } from './npc.js';

const session = '00000000-0000-4000-8000-000000000001';
const uuid = '00000000-0000-4000-8000-000000000002';
const state = () => ({ protocol: 1, backend: 'npc_prototype', source: 'integrated_server',
    timestamp: Date.now(), session, dimension: 0, loadedOnly: true,
    npcs: [{ uuid, alive: true, health: 20, command: 'hold', movement: 'holding', position: { x: 0, y: 64, z: 0 } }] });

async function fixture(t, handler) {
    const token = 'test-only-npc-token-with-at-least-32-characters';
    const server = createServer(async (request, response) => {
        try {
            assert.equal(request.headers.authorization, `Bearer ${token}`);
            const reply = (data, status = 200) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(data)); };
            await handler(request, reply);
        } catch (error) { response.writeHead(500); response.end(JSON.stringify({ error: error.message })); }
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
    return new DregoraNpcAdapter({ url: `http://127.0.0.1:${server.address().port}`, token });
}

test('NPC orders reject player controls and malformed retreat destinations', () => {
    for (const args of [
        ['bad', 'follow'], [uuid, 'attack'], [uuid, 'retreat'], [uuid, 'hold', { x: 1, y: 64, z: 1 }],
        [uuid, 'retreat', { x: 1, y: NaN, z: 1 }], [uuid, 'retreat', { x: 1, y: '64', z: 1 }],
        [uuid, 'retreat', { x: 1, y: 64, z: 1, script: 'anything' }],
    ]) assert.throws(() => validateNpcOrder(...args));
    assert.deepEqual(validateNpcOrder(uuid, 'retreat', { x: 1, y: 64, z: 1 }),
        { uuid, command: 'retreat', destination: { x: 1, y: 64, z: 1 } });
});

test('NPC command uses UUID and its server session without accessing player endpoints', async t => {
    let submitted;
    const adapter = await fixture(t, async (request, reply) => {
        if (request.url === '/v1/npcs') return reply(state());
        assert.equal(request.url, '/v1/npcs/commands');
        assert.equal(request.method, 'POST');
        let body = ''; for await (const part of request) body += part;
        submitted = JSON.parse(body);
        reply({ ...submitted, status: 'completed', reason: 'command_set', effectVerified: false });
    });
    const result = await adapter.command(uuid, 'follow', { id: 'npc-follow' });
    assert.equal(submitted.session, session); assert.equal(submitted.uuid, uuid);
    assert.equal(result.effectVerified, false);
});

test('stale sessions and missing NPCs are rejected before command submission', async t => {
    const adapter = await fixture(t, (request, reply) => { assert.equal(request.url, '/v1/npcs'); reply(state()); });
    await assert.rejects(adapter.command(uuid, 'follow', { session: 'old-session' }), { code: 'stale_npc_session' });
    await assert.rejects(adapter.command(session, 'hold'), { code: 'npc_not_loaded_or_alive' });
});

test('navigation acknowledgment cannot claim a verified effect', async t => {
    const adapter = await fixture(t, async (request, reply) => {
        if (request.url === '/v1/npcs') return reply(state());
        let body = ''; for await (const part of request) body += part;
        reply({ ...JSON.parse(body), status: 'completed', reason: 'command_set', effectVerified: true });
    });
    await assert.rejects(adapter.command(uuid, 'hold'), { code: 'invalid_npc_result' });
});

test('uncertain network failures preserve command identity for deduplicated recovery', async t => {
    const adapter = await fixture(t, (request, reply) => {
        if (request.url === '/v1/npcs') return reply(state());
        request.socket.destroy();
    });
    await assert.rejects(adapter.command(uuid, 'follow', { id: 'recover-npc' }), error =>
        error.code === 'bridge_unreachable' && error.details.actionId === 'recover-npc'
        && error.details.session === session && error.details.uuid === uuid);
});

test('stale or nonauthoritative NPC state is rejected', async t => {
    let stale = true;
    const adapter = await fixture(t, (request, reply) => reply(stale ? { ...state(), timestamp: Date.now() - 10000 }
        : { ...state(), source: 'client_synced' }));
    await assert.rejects(adapter.getState(), { code: 'stale_state' }); stale = false;
    await assert.rejects(adapter.getState(), { code: 'invalid_npc_state' });
});
