// Measures the engine the Electron client drives: identity creation and unlock, relay registration,
// the signed key exchange, and per-message latency through a live relay.
//
// Needs, in order: `./gradlew :desktop-engine:installDist`, `npm run build:main`, and a relay on
// localhost:8080 (`./gradlew :chat-server:run`). Then: `node scripts/measure.js [out.json]`.
//
// Read the numbers for what they are. Everything runs on one machine over the loopback interface, so
// the network contributes almost nothing; what is measured is the cost of the cryptography, the
// engine, the pipe and the relay's routing. They are not a prediction of latency over the internet.
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');

const root = path.resolve(__dirname, '..', '..');
const { EngineClient } = require(path.join(root, 'chat-desktop-electron', 'dist', 'main', 'engineClient.js'));

const install = path.join(root, 'desktop-engine', 'build', 'install', 'desktop-engine', 'lib', '*');
const exe = process.platform === 'win32' ? 'java.exe' : 'java';
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', exe) : exe;

const MESSAGES = 100;
const UNLOCK_RUNS = 5;

function launch(dir) {
  const c = new EngineClient(java, ['-cp', install, 'com.e2eechat.engine.EngineMain'], {
    JAVA_TOOL_OPTIONS: '-Dtetherless.config.dir=' + dir,
  }, path.join(root, 'desktop-engine'));
  c.events = [];
  c.onEvent((event, payload) => c.events.push({ event, payload, at: process.hrtime.bigint() }));
  c.start();
  return c;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const ms = (from, to) => Number(to - from) / 1e6;
const now = () => process.hrtime.bigint();

async function until(label, fn, limit = 20000) {
  const start = Date.now();
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() - start > limit) throw new Error('timed out waiting for ' + label);
    await sleep(5);
  }
}

function stats(samples) {
  const s = [...samples].sort((a, b) => a - b);
  const q = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
  const mean = s.reduce((a, b) => a + b, 0) / s.length;
  return {
    n: s.length,
    min: +s[0].toFixed(2),
    median: +q(0.5).toFixed(2),
    mean: +mean.toFixed(2),
    p95: +q(0.95).toFixed(2),
    max: +s[s.length - 1].toFixed(2),
  };
}

(async () => {
  const result = { machine: {}, measurements: {} };
  result.machine = {
    os: `${os.type()} ${os.release()}`,
    cpu: os.cpus()[0].model.trim(),
    logicalCpus: os.cpus().length,
    memoryGiB: +(os.totalmem() / 2 ** 30).toFixed(1),
    node: process.version,
  };

  // ---- identity creation and unlock -------------------------------------------------------------
  const created = [];
  const reopened = [];
  for (let i = 0; i < UNLOCK_RUNS; i++) {
    const dir = path.join(os.tmpdir(), 'tetherless-measure-unlock-' + i);
    fs.rmSync(dir, { recursive: true, force: true });

    let eng = launch(dir);
    await sleep(2500); // JVM start is deliberately excluded: it is not what the user waits on after typing.
    let t = now();
    await eng.invoke('unlock', { passphrase: 'a reasonably long passphrase', displayName: 'Measure' });
    created.push(ms(t, now()));
    await eng.stop();

    eng = launch(dir);
    await sleep(2500);
    t = now();
    await eng.invoke('unlock', { passphrase: 'a reasonably long passphrase' });
    reopened.push(ms(t, now()));
    await eng.stop();
  }
  result.measurements.identityCreation = stats(created);
  result.measurements.unlockExistingIdentity = stats(reopened);

  // ---- two engines, one relay -------------------------------------------------------------------
  const aliceDir = path.join(os.tmpdir(), 'tetherless-measure-alice');
  const bobDir = path.join(os.tmpdir(), 'tetherless-measure-bob');
  fs.rmSync(aliceDir, { recursive: true, force: true });
  fs.rmSync(bobDir, { recursive: true, force: true });
  const alice = launch(aliceDir);
  const bob = launch(bobDir);
  await sleep(2500);
  const a = await alice.invoke('unlock', { passphrase: 'alice measure pass 1', displayName: 'Alice' });
  const b = await bob.invoke('unlock', { passphrase: 'bob measure pass 2', displayName: 'Bob' });

  // Relay registration: from asking to connect until the relay has acknowledged and CONNECTED is reported.
  const reg = [];
  for (const eng of [alice, bob]) {
    const t = now();
    await eng.invoke('connect', {});
    await until('connected', async () => (await eng.invoke('status', {})).connectionState === 'CONNECTED');
    reg.push(ms(t, now()));
  }
  result.measurements.relayRegistration = stats(reg);

  // The signed Diffie-Hellman exchange: from starting it until Alice holds Bob's key.
  const t0 = now();
  await alice.invoke('startSecureChat', { peerId: b.clientId });
  await until('handshake', async () => (await alice.invoke('fingerprint', { peerId: b.clientId })).theirs);
  result.measurements.keyExchange = stats([ms(t0, now())]);
  await sleep(300);

  // ---- per-message latency ----------------------------------------------------------------------
  const oneWay = [];
  const ack = [];
  for (let i = 0; i < MESSAGES; i++) {
    const text = 'latency probe ' + i + ' ' + 'x'.repeat(40);
    const before = bob.events.length;
    const t = now();
    const { sent } = await alice.invoke('send', { peerId: b.clientId, text });
    const sentAt = now();
    const arrived = await until('delivery ' + i, () =>
      bob.events.slice(before).find((e) => e.event === 'message' && e.payload.text === text));
    oneWay.push(ms(t, arrived.at));
    const acked = await until('ack ' + i, () =>
      alice.events.find((e) => e.event === 'deliveryStatus' && e.payload.messageId === sent.messageId));
    ack.push(ms(t, acked.at));
    void sentAt;
  }
  result.measurements.messageOneWay = stats(oneWay);
  result.measurements.messageToDeliveryAck = stats(ack);

  // ---- reading and searching what is now stored -------------------------------------------------
  const hist = [];
  for (let i = 0; i < 20; i++) {
    const t = now();
    await alice.invoke('history', { peerId: b.clientId, limit: 200 });
    hist.push(ms(t, now()));
  }
  result.measurements.historyLoad100 = stats(hist);

  const srch = [];
  for (let i = 0; i < 20; i++) {
    const t = now();
    await alice.invoke('search', { query: 'probe 7', limit: 50 });
    srch.push(ms(t, now()));
  }
  result.measurements.searchOver100 = stats(srch);
  result.measurements.storedMessages = MESSAGES;

  await alice.stop();
  await bob.stop();

  const out = process.argv[2] || path.join(os.tmpdir(), 'tetherless-measurements.json');
  fs.writeFileSync(out, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
  console.log('\nwritten to ' + out);
  process.exit(0);
})().catch((e) => {
  console.error('FAILED:', e.message || e);
  process.exit(1);
});
