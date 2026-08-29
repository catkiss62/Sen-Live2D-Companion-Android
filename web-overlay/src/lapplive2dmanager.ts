/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

import { CubismMatrix44 } from '@framework/math/cubismmatrix44';
import { ACubismMotion } from '@framework/motion/acubismmotion';
import { CubismWebGLOffscreenManager } from '@framework/rendering/cubismoffscreenmanager';
import * as LAppDefine from './lappdefine';
import { LAppModel } from './lappmodel';
import { LAppPal } from './lapppal';
import { LAppSubdelegate } from './lappsubdelegate';

type AndroidBridge = {
  onStageStatus?: (message: string) => void;
  onStageError?: (message: string) => void;
};

let activeManager: LAppLive2DManager | null = null;
export const getActiveManager = (): LAppLive2DManager | null => activeManager;

function notifyStatus(message: string): void {
  const android = (globalThis as unknown as { AndroidStage?: AndroidBridge }).AndroidStage;
  android?.onStageStatus?.(message);
}

function notifyError(message: string): void {
  const android = (globalThis as unknown as { AndroidStage?: AndroidBridge }).AndroidStage;
  android?.onStageError?.(message);
}

function modelRequest(): { dir: string; file: string; saved: string[] } | null {
  const params = new URLSearchParams(window.location.search);
  const raw = params.get('model');
  if (!raw) return null;
  const slash = raw.lastIndexOf('/');
  if (slash < 0) return null;
  let saved: string[] = [];
  try {
    const parsed = JSON.parse(params.get('saved') ?? '[]');
    if (Array.isArray(parsed)) saved = parsed.filter(value => typeof value === 'string');
  } catch {
    // Invalid saved-expression metadata should never block model rendering.
  }
  return { dir: raw.substring(0, slash + 1), file: raw.substring(slash + 1), saved };
}

export class LAppLive2DManager {
  private releaseAllModel(): void {
    this._models.length = 0;
  }

  public setOffscreenSize(width: number, height: number): void {
    for (const model of this._models) model?.setRenderTargetSize(width, height);
  }

  public onDrag(x: number, y: number): void {
    this._models[0]?.setDragging(x, y);
  }

  public onTap(_x: number, _y: number): void {
    // Static-parity milestone: no random tap expressions or motions yet.
  }

  public applyExpressions(names: string[]): boolean {
    const model = this._models[0];
    if (!model || !this._ready) {
      this._pendingExpressions = [...names];
      return false;
    }
    for (const name of names) model.setExpression(name);
    notifyStatus(names.length > 0 ? `已应用 VTS 启动预设：${names.join('、')}` : '当前没有 VTS 启动预设');
    return true;
  }

  public onUpdate(): void {
    if (this._models.length === 0) return;
    const gl = this._subdelegate.getGl();
    CubismWebGLOffscreenManager.getInstance().beginFrameProcess(gl);

    const { width, height } = this._subdelegate.getCanvas();
    const projection = new CubismMatrix44();
    const model = this._models[0];
    if (model.getModel()) {
      if (model.getModel().getCanvasWidth() > 1.0 && width < height) {
        model.getModelMatrix().setWidth(2.0);
        projection.scale(1.0, width / height);
      } else {
        projection.scale(height / width, 1.0);
      }
      projection.multiplyByMatrix(this._viewMatrix);
    }

    model.update();
    model.draw(projection);

    // CompleteSetup is the final value (23) in the official sample's LoadStep enum.
    const state = (model as unknown as { _state?: number })._state;
    if (!this._ready && state === 23) {
      this._ready = true;
      const pending = [...this._pendingExpressions];
      this._pendingExpressions.length = 0;
      if (pending.length > 0) {
        for (const name of pending) model.setExpression(name);
      }
      notifyStatus(pending.length > 0
        ? `模型已就绪 · 已恢复 VTS 启动预设：${pending.join('、')}`
        : '模型已就绪 · 原始状态');
    }

    CubismWebGLOffscreenManager.getInstance().endFrameProcess(gl);
    CubismWebGLOffscreenManager.getInstance().releaseStaleRenderTextures(gl);
  }

  public nextScene(): void {}

  private changeScene(index: number): void {
    this._sceneIndex = index;
    const request = modelRequest();
    if (!request) {
      notifyStatus('等待导入 Sen 模型 ZIP');
      return;
    }

    this.releaseAllModel();
    this._ready = false;
    this._pendingExpressions = [...request.saved];
    const instance = new LAppModel();
    instance.setSubdelegate(this._subdelegate);
    instance.loadAssets(request.dir, request.file);
    this._models.push(instance);
    notifyStatus('正在加载模型与 2K 贴图…');
  }

  public setViewMatrix(matrix: CubismMatrix44): void {
    for (let i = 0; i < 16; i++) this._viewMatrix.getArray()[i] = matrix.getArray()[i];
  }

  public addModel(sceneIndex = 0): void {
    this.changeScene(sceneIndex);
  }

  public constructor() {
    this._subdelegate = null;
    this._viewMatrix = new CubismMatrix44();
    this._models = [];
    this._sceneIndex = 0;
    this._ready = false;
    this._pendingExpressions = [];
    activeManager = this;
  }

  public release(): void {
    if (activeManager === this) activeManager = null;
  }

  public initialize(subdelegate: LAppSubdelegate): void {
    this._subdelegate = subdelegate;
    try {
      this.changeScene(this._sceneIndex);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      LAppPal.printMessage(message);
      notifyError(message);
    }
  }

  private _subdelegate: LAppSubdelegate;
  private _viewMatrix: CubismMatrix44;
  private _models: LAppModel[];
  private _sceneIndex: number;
  private _ready: boolean;
  private _pendingExpressions: string[];

  beganMotion = (self: ACubismMotion): void => console.log('Motion began', self);
  finishedMotion = (self: ACubismMotion): void => console.log('Motion finished', self);
}

