import { app, BrowserWindow, ipcMain, shell } from 'electron';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { EngineClient } from './engineClient';
import type { CommandName, Commands, EventName } from '../shared/protocol';

const isDev = !app.isPackaged;

interface Launch {
  command: string;
  args: string[];
}

/**
 * How to start the engine.
 *
 * The JVM is invoked directly rather than through Gradle's generated `.bat`. Node refuses to spawn
 * a batch file without a shell (`EINVAL`, since the CVE-2024-27980 hardening), and switching
 * `shell: true` on would drag in quoting rules and a command-injection surface for no benefit. A
 * wildcard classpath is expanded by java itself, so no shell is involved at any point.
 *
 * In development the classes come from the Gradle install image, so `gradlew
 * :desktop-engine:installDist` is the only build step needed. A packaged build uses the jlink
 * runtime staged beside them.
 */
function engineLaunch(): Launch {
  const mainClass = 'com.e2eechat.engine.EngineMain';
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';

  const packagedRoot = join(process.resourcesPath, 'engine');
  if (!isDev && existsSync(packagedRoot)) {
    return {
      command: join(packagedRoot, 'runtime', 'bin', exe),
      args: ['-cp', join(packagedRoot, 'lib', '*'), mainClass],
    };
  }

  const install = join(
    app.getAppPath(),
    '..',
    'desktop-engine',
    'build',
    'install',
    'desktop-engine',
  );
  const javaHome = process.env.JAVA_HOME;
  return {
    command: javaHome ? join(javaHome, 'bin', exe) : exe,
    args: ['-cp', join(install, 'lib', '*'), mainClass],
  };
}

let engine: EngineClient | null = null;
let window: BrowserWindow | null = null;

function createWindow(): void {
  window = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0B0D10',
    show: false,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      // The renderer displays text written by other people. Every one of these is load-bearing:
      // an XSS in a chat transcript becomes remote code execution the moment any of them is
      // relaxed.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      spellcheck: false,
    },
  });

  window.once('ready-to-show', () => window?.show());

  // Nothing in this app should ever navigate, and a peer-supplied link must never open in the app's
  // own window. External links go to the system browser, everything else is refused.
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) {
      void shell.openExternal(url);
    }
    return { action: 'deny' };
  });
  window.webContents.on('will-navigate', (event) => event.preventDefault());

  if (isDev && process.env.VITE_DEV_SERVER_URL) {
    void window.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    void window.loadFile(join(__dirname, '../renderer/index.html'));
  }
}

function startEngine(): void {
  const env: NodeJS.ProcessEnv = {};
  if (process.env.TETHERLESS_CONFIG_DIR) {
    // A JVM flag rather than a start-script variable, since the start script is no longer involved.
    env.JAVA_TOOL_OPTIONS = `-Dtetherless.config.dir=${process.env.TETHERLESS_CONFIG_DIR}`;
  }
  const launch = engineLaunch();
  engine = new EngineClient(launch.command, launch.args, env);

  engine.onEvent((event: EventName, payload) => {
    window?.webContents.send('engine:event', { event, payload });
  });
  engine.on('stderr', (text: string) => {
    if (isDev) {
      process.stderr.write(`[engine] ${text}`);
    }
  });
  engine.start();
}

app.whenReady().then(() => {
  // One channel in, one channel out. The renderer cannot reach the engine any other way, and the
  // preload bridge exposes nothing else.
  ipcMain.handle(
    'engine:invoke',
    async (_event, command: CommandName, payload: Commands[CommandName]['in']) => {
      if (!engine) {
        return { ok: false, error: { code: 'engine_down', message: 'The engine is not running' } };
      }
      try {
        const result = await engine.invoke(command, payload);
        return { ok: true, result };
      } catch (error) {
        return { ok: false, error };
      }
    },
  );

  startEngine();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  void engine?.stop().finally(() => app.quit());
});
