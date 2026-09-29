import { contextBridge, ipcRenderer } from 'electron';
import type {
  CommandName,
  Commands,
  EngineError,
  EventName,
  Events,
} from '../shared/protocol';

/**
 * The entire surface the renderer is given.
 *
 * Two functions, no Node, no filesystem, no process. Anything added here is reachable by whatever
 * ends up running in the renderer, including text a peer sent — so the list stays this short on
 * purpose.
 */
const bridge = {
  async invoke<K extends CommandName>(
    command: K,
    payload?: Commands[K]['in'],
  ): Promise<Commands[K]['out']> {
    const reply = (await ipcRenderer.invoke('engine:invoke', command, payload ?? {})) as
      | { ok: true; result: Commands[K]['out'] }
      | { ok: false; error: EngineError };
    if (reply.ok) {
      return reply.result;
    }
    throw reply.error;
  },

  on<K extends EventName>(event: K, handler: (payload: Events[K]) => void): () => void {
    const listener = (_e: unknown, frame: { event: EventName; payload: unknown }): void => {
      if (frame.event === event) {
        handler(frame.payload as Events[K]);
      }
    };
    ipcRenderer.on('engine:event', listener);
    return () => ipcRenderer.removeListener('engine:event', listener);
  },
};

contextBridge.exposeInMainWorld('tetherless', bridge);
