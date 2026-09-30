// Two engines, one relay: proves a message crosses between two parties through the real stack.
// Uses the compiled EngineClient - the same class Electron's main process uses.
//
// Needs, in order: `./gradlew :desktop-engine:installDist`, `npm run build:main`, and a relay
// listening on localhost:8080 (`./gradlew :chat-server:run`). Then: `npm run e2e`.
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const root = path.resolve(__dirname, '..', '..');
const { EngineClient } = require(path.join(root, 'chat-desktop-electron', 'dist', 'main', 'engineClient.js'));

const install = path.join(root, 'desktop-engine/build/install/desktop-engine/lib', '*');
const exe = process.platform === 'win32' ? 'java.exe' : 'java';
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', exe) : exe;

function launch(name) {
  const dir = path.join(os.tmpdir(), 'tetherless-e2e-' + name);
  fs.rmSync(dir, { recursive: true, force: true });
  const c = new EngineClient(java, ['-cp', install, 'com.e2eechat.engine.EngineMain'], {
    JAVA_TOOL_OPTIONS: '-Dtetherless.config.dir=' + dir,
  }, path.join(root, 'desktop-engine'));
  c.events = [];
  c.onEvent((event, payload) => c.events.push({ event, payload }));
  c.on('stderr', (t) => { if (/WARN|ERROR|Exception|TLS|SSL|trust|cert/i.test(t)) process.stderr.write('[' + name + '] ' + t); });
  c.start();
  return c;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(label, fn, ms = 15000) {
  const t = Date.now();
  while (Date.now() - t < ms) {
    const v = await fn();
    if (v) return v;
    await sleep(150);
  }
  throw new Error('timed out waiting for: ' + label);
}
const ok = (m) => console.log('  PASS ' + m);

(async () => {
  const alice = launch('alice');
  const bob = launch('bob');
  await sleep(2500); // JVM start

  const a = await alice.invoke('unlock', { passphrase: 'alice passphrase 1', displayName: 'Alice' });
  const b = await bob.invoke('unlock', { passphrase: 'bob passphrase 2', displayName: 'Bob' });
  ok('both identities created, distinct ids: ' + (a.clientId !== b.clientId));

  await alice.invoke('connect', {});
  await bob.invoke('connect', {});
  await until('both CONNECTED', async () => {
    const [sa, sb] = [await alice.invoke('status', {}), await bob.invoke('status', {})];
    return sa.connectionState === 'CONNECTED' && sb.connectionState === 'CONNECTED';
  });
  ok('both clients registered with the relay (CONNECTED)');

  await alice.invoke('startSecureChat', { peerId: b.clientId });
  await until('handshake: Alice holds Bob key', async () => {
    const f = await alice.invoke('fingerprint', { peerId: b.clientId });
    return f.theirs;
  });
  ok('signed key exchange completed; Alice sees a fingerprint for Bob');

  const sent = await alice.invoke('send', { peerId: b.clientId, text: 'hello from Alice \u2014 "quotes", \nnewline, \u{1F512}' });
  if (!sent.sent) throw new Error('send returned null');
  ok('send accepted, status=' + sent.sent.status);

  const got = await until('Bob receives the message', () =>
    bob.events.find((e) => e.event === 'message'));
  const expected = 'hello from Alice \u2014 "quotes", \nnewline, \u{1F512}';
  if (got.payload.text !== expected) throw new Error('text corrupted in transit: ' + got.payload.text);
  ok('Bob received it intact (quotes, newline, emoji survive the JSON pipe)');
  if (got.payload.peerId !== a.clientId) throw new Error('wrong sender id');
  ok('sender id on the incoming message is Alice');

  await until('Alice sees DELIVERED', () =>
    alice.events.find((e) => e.event === 'deliveryStatus' && e.payload.messageId === sent.sent.messageId));
  ok('delivery acknowledgement came back to Alice');

  await bob.invoke('send', { peerId: a.clientId, text: 'hi Alice' });
  await until('Alice receives the reply', () =>
    alice.events.find((e) => e.event === 'message' && e.payload.text === 'hi Alice'));
  ok('reply crossed the other way');

  const hist = await alice.invoke('history', { peerId: b.clientId, limit: 50 });
  ok('history persisted on Alice: ' + hist.messages.length + ' messages');
  const list = await bob.invoke('listConversations', {});
  ok('Bob conversation list: ' + list.conversations.length + ' conversation, unread=' + list.conversations[0].unread);

  // ---- search: the engine command behind the search box ----
  const hit = await alice.invoke('search', { query: 'hello', limit: 50 });
  if (hit.messages.length !== 1 || hit.messages[0].peerId !== b.clientId)
    throw new Error('search for "hello" should find exactly the one message, got ' + JSON.stringify(hit.messages));
  ok('search finds the message and reports which conversation it belongs to');

  const upper = await alice.invoke('search', { query: 'HELLO', limit: 50 });
  if (upper.messages.length !== 1) throw new Error('search should ignore case');
  ok('search ignores case');

  const emoji = await alice.invoke('search', { query: '\u{1F512}', limit: 50 });
  if (emoji.messages.length !== 1) throw new Error('search for the emoji failed');
  ok('search matches an emoji stored encrypted at rest');

  const none = await alice.invoke('search', { query: 'zzz-not-present', limit: 50 });
  if (none.messages.length !== 0) throw new Error('a query with no match must return nothing');
  ok('a query with no match returns nothing');

  // If the repository matched with SQL LIKE, "%" would be a wildcard and return every message.
  const wildcard = await alice.invoke('search', { query: '%', limit: 50 });
  if (wildcard.messages.length !== 0) throw new Error('"%" acted as a wildcard: ' + wildcard.messages.length);
  ok('"%" is matched literally, not as a wildcard');

  // ---- status: what the settings panel displays ----
  const st = await alice.invoke('status', {});
  if (st.displayName !== 'Alice' || st.clientId !== a.clientId || !st.fingerprint || !st.relay)
    throw new Error('status is missing what the settings panel shows: ' + JSON.stringify(st));
  ok('status carries name, peer id, safety number and relay for the settings panel');

  const fa = await alice.invoke('fingerprint', { peerId: b.clientId });
  const fb = await bob.invoke('fingerprint', { peerId: a.clientId });
  const strip = (s) => String(s).replace(/[^0-9a-f]/gi, '').toLowerCase();
  if (strip(fa.theirs) !== strip(fb.ours) || strip(fb.theirs) !== strip(fa.ours))
    throw new Error('safety numbers do not match across the two sides');
  ok('safety numbers agree: what Alice sees of Bob is what Bob sees of himself');

  await alice.stop();
  await bob.stop();
  console.log('\nROUND TRIP OK');
  process.exit(0);
})().catch((e) => { console.error('\nFAILED:', e.message || e); process.exit(1); });
