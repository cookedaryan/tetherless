import { spawn, ChildProcessWithoutNullStreams } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { createInterface, Interface } from 'node:readline';
import type {
  CommandName,
  Commands,
  EngineError,
  EventName,
  Events,
} from '../shared/protocol';

interface Pending {
  resolve: (value: unknown) => void;
  reject: (reason: EngineError) => void;
}

/**
 * Owns the Java engine process and the frame stream to it.
 *
 * The channel is this process's pipe to its own child. Nothing listens on a port: the passphrase and
 * every plaintext message cross this boundary, and a loopback socket would be reachable by anything
 * else running as the same user.
 */
export class EngineClient extends EventEmitter {
  private child: ChildProcessWithoutNullStreams | null = null;
  private reader: Interface | null = null;
  private readonly pending = new Map<string, Pending>();
  private nextId = 1;
  private stopping = false;

  constructor(
    private readonly command: string,
    private readonly args: string[] = [],
    private readonly env: NodeJS.ProcessEnv = {},
    /**
     * The engine's working directory, which is not cosmetic: in development the engine finds the
     * pinned development certificate at `../chat-server/src/main/resources/`, relative to where it
     * runs. Inheriting the caller's directory made TLS trust depend on where Electron happened to
     * be launched from.
     */
    private readonly cwd?: string,
  ) {
    super();
  }

  start(): void {
    if (this.child) {
      return;
    }
    this.stopping = false;
    try {
      this.child = spawn(this.command, this.args, {
        env: { ...process.env, ...this.env },
        cwd: this.cwd,
        stdio: ['pipe', 'pipe', 'pipe'],
        // No shell, deliberately: the wildcard classpath is expanded by java, and a shell here
        // would be a command-injection surface for nothing.
        shell: false,
        windowsHide: true,
      });
    } catch (error) {
      // A missing JVM or a bad path must reach the window as a state it can show, not die as an
      // unhandled rejection in the main process with a blank screen in front of the user.
      this.emit('stderr', `failed to start the engine: ${String(error)}\n`);
      this.emit('event', 'engineDown', { code: null });
      this.child = null;
      return;
    }

    this.reader = createInterface({ input: this.child.stdout });
    this.reader.on('line', (line) => this.onLine(line));

    // The engine points System.out at stderr precisely so that anything which thinks it is printing
    // to the console lands here instead of corrupting the frame stream. Surfaced for debugging, and
    // never parsed.
    this.child.stderr.on('data', (chunk: Buffer) => {
      this.emit('stderr', chunk.toString('utf8'));
    });

    this.child.on('exit', (code) => {
      const failures = Array.from(this.pending.values());
      this.pending.clear();
      for (const p of failures) {
        p.reject({ code: 'engine_down', message: 'The engine stopped before answering' });
      }
      this.child = null;
      this.reader?.close();
      this.reader = null;
      if (!this.stopping) {
        // A crash with unsent work must be visible. The renderer shows a disconnected state rather
        // than a window that has quietly stopped working.
        this.emit('event', 'engineDown', { code });
      }
    });
  }

  private onLine(line: string): void {
    if (!line.trim()) {
      return;
    }
    let frame: Record<string, unknown>;
    try {
      frame = JSON.parse(line) as Record<string, unknown>;
    } catch {
      this.emit('stderr', `unparseable frame: ${line}\n`);
      return;
    }

    if (typeof frame.event === 'string') {
      this.emit('event', frame.event as EventName, frame.payload ?? {});
      return;
    }

    const id = typeof frame.id === 'string' ? frame.id : null;
    if (!id) {
      return;
    }
    const waiting = this.pending.get(id);
    if (!waiting) {
      return;
    }
    this.pending.delete(id);
    if (frame.ok === true) {
      waiting.resolve(frame.result ?? {});
    } else {
      const error = (frame.error ?? {}) as Partial<EngineError>;
      waiting.reject({
        code: error.code ?? 'engine_error',
        message: error.message ?? 'The engine refused the command',
      });
    }
  }

  invoke<K extends CommandName>(
    command: K,
    payload?: Commands[K]['in'],
  ): Promise<Commands[K]['out']> {
    if (!this.child) {
      return Promise.reject({
        code: 'engine_down',
        message: 'The engine is not running',
      } as EngineError);
    }
    const id = String(this.nextId++);
    const frame = JSON.stringify({ id, cmd: command, payload: payload ?? {} });
    return new Promise<Commands[K]['out']>((resolve, reject) => {
      this.pending.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
      });
      this.child!.stdin.write(`${frame}\n`, 'utf8');
    });
  }

  /** Emitted events, typed at the call site. */
  onEvent<K extends EventName>(handler: (event: K, payload: Events[K]) => void): void {
    this.on('event', handler as (event: string, payload: unknown) => void);
  }

  async stop(): Promise<void> {
    if (!this.child) {
      return;
    }
    this.stopping = true;
    try {
      await this.invoke('shutdown', {});
    } catch {
      // Already gone, or refusing to answer. Either way the kill below settles it.
    }
    this.child?.kill();
    this.child = null;
  }
}
