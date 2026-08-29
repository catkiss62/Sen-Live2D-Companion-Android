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

window.addEventListener('beforeunload', (): void => LAppDelegate.releaseInstance(), { passive: true });
