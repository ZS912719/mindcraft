import { DregoraAdapter } from '../../src/adapters/dregora/client.js';

try {
    const adapter = new DregoraAdapter();
    const state = await adapter.getState();
    console.log(JSON.stringify(state, null, 2));
} catch (error) {
    console.error(`Dregora bridge: ${error.code || error.message}`);
    process.exitCode = 1;
}
