type AndroidBridge = {
  onStageStatus?: (message: string) => void;
  onStageError?: (message: string) => void;
};

const bridge = (): AndroidBridge | undefined =>
  (globalThis as unknown as { AndroidStage?: AndroidBridge }).AndroidStage;

let pageIsUnloading = false;

function status(message: string): void {
  const label = document.getElementById('boot-status');
  if (label) label.textContent = message;
  bridge()?.onStageStatus?.(message);
}

function fail(error: unknown): void {
  if (pageIsUnloading) return;
  const message = error instanceof Error ? error.message : String(error);
  status(`启动失败：${message}`);
  bridge()?.onStageError?.(message);
}

function loadClassicScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`无法加载 ${src}`));
    document.head.appendChild(script);
  });
}

async function boot(): Promise<void> {
  const coreCandidates = [
    'https://appassets.androidplatform.net/runtime/live2dcubismcore.min.js',
    'https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js'
  ];

  let lastError: unknown;
  for (const candidate of coreCandidates) {
    try {
      status(candidate.includes('/runtime/') ? '正在检查本地 Cubism Core…' : '正在加载 Live2D 官方 Cubism Core…');
      await loadClassicScript(candidate);
      if ((globalThis as unknown as { Live2DCubismCore?: unknown }).Live2DCubismCore) {
        await import('./main');
        return;
      }
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError ?? new Error('Cubism Core 未加载');
}

window.addEventListener('error', event => fail(event.error ?? event.message));
window.addEventListener('unhandledrejection', event => fail(event.reason));
window.addEventListener('beforeunload', () => { pageIsUnloading = true; }, {
  capture: true,
  once: true
});
void boot().catch(fail);
