/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

import { LAppDelegate } from './lappdelegate';
import { getActiveManager } from './lapplive2dmanager';

type AndroidBridge = {
  onStageStatus?: (message: string) => void;
  onStageError?: (message: string) => void;
  onWebGlContextLost?: (message: string) => void;
};

declare global {
  interface Window {
    SenStage?: {
      applyExpressions: (names: string[]) => boolean;
      reload: () => void;
    };
    AndroidStage?: AndroidBridge;
  }
}

window.SenStage = {
  applyExpressions(names: string[]): boolean {
    return getActiveManager()?.applyExpressions(names) ?? false;
  },
  reload(): void {
    window.location.reload();
  }
};

function startStage(): void {
  const bootLabel = document.getElementById('boot-status');
  if (bootLabel) bootLabel.remove();
  if (!LAppDelegate.getInstance().initialize()) {
    window.AndroidStage?.onStageError?.('WebGL 初始化失败');
    return;
  }
  LAppDelegate.getInstance().run();
}

if (document.readyState === 'complete') {
  startStage();
} else {
  window.addEventListener('load', startStage, { passive: true, once: true });
}

document.addEventListener('webglcontextlost', (event: Event): void => {
  event.preventDefault();
  const quality = new URLSearchParams(window.location.search).get('quality') === '1k'
    ? '1K兼容'
    : '原始2K';
  window.AndroidStage?.onWebGlContextLost?.(`${quality}加载时 WebGL 显存上下文丢失`);
}, { capture: true, passive: false });

window.addEventListener('beforeunload', (): void => {
  try {
    LAppDelegate.releaseInstance();
  } catch (error) {
    // The WebView is already discarding this page. A partial sample resource
    // must not overwrite the next page's real loading status.
    console.warn('Ignoring teardown error during navigation', error);
  }
}, { passive: true });
