/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

import { LAppGlManager } from './lappglmanager';

/**
 * Mobile-oriented version of the official sample texture manager.
 *
 * Sen uses 26 2048px textures. Keeping decoded HTMLImageElements plus GPU
 * mipmaps almost doubles the memory required by the source textures. The
 * Android stage keeps full-resolution GPU textures, but releases decoded CPU
 * images after upload and uses LINEAR filtering without mipmaps.
 */
export class LAppTextureManager {
  public constructor() {
    this._textures = [];
  }

  public release(): void {
    for (const texture of this._textures) {
      if (texture?.id) this._glManager.getGl().deleteTexture(texture.id);
    }
    this._textures = null;
  }

  public createTextureFromPngFile(
    fileName: string,
    usePremultiply: boolean,
    callback: (textureInfo: TextureInfo) => void,
    errorCallback?: (message: string) => void
  ): void {
    const cached = this._textures?.find(
      texture => texture.fileName === fileName && texture.usePremultply === usePremultiply
    );
    if (cached) {
      callback(cached);
      return;
    }

    const img = new Image();
    img.addEventListener('error', () => {
      errorCallback?.(`无法解码贴图：${fileName}`);
    }, { passive: true, once: true });

    img.addEventListener('load', (): void => {
      const gl = this._glManager.getGl();
      let texture: WebGLTexture | null = null;
      try {
        // Remove stale GL errors so a failure can be attributed to this upload.
        while (gl.getError() !== gl.NO_ERROR) {
          // Drain the error queue.
        }

        texture = gl.createTexture();
        if (!texture) throw new Error('WebGL 无法创建纹理，可能已达到显存上限');
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, usePremultiply ? 1 : 0);
        gl.texImage2D(
          gl.TEXTURE_2D,
          0,
          gl.RGBA,
          gl.RGBA,
          gl.UNSIGNED_BYTE,
          img
        );

        const error = gl.getError();
        if (error !== gl.NO_ERROR) {
          throw new Error(`WebGL 上传失败 0x${error.toString(16)}，可能是显存不足`);
        }
        gl.bindTexture(gl.TEXTURE_2D, null);

        const info = new TextureInfo();
        info.fileName = fileName;
        info.width = img.width;
        info.height = img.height;
        info.id = texture;
        info.usePremultply = usePremultiply;
        // Do not retain the decoded 16 MiB HTMLImageElement for each 2K texture.
        info.img = null;
        this._textures.push(info);
        callback(info);
      } catch (error) {
        if (texture) gl.deleteTexture(texture);
        gl.bindTexture(gl.TEXTURE_2D, null);
        const detail = error instanceof Error ? error.message : String(error);
        errorCallback?.(`${detail}：${fileName}`);
      }
    }, { passive: true, once: true });
    img.src = fileName;
  }

  public releaseTextures(): void {
    const gl = this._glManager.getGl();
    for (const texture of this._textures) {
      if (texture?.id) gl.deleteTexture(texture.id);
    }
    this._textures.length = 0;
  }

  public releaseTextureByTexture(texture: WebGLTexture): void {
    const index = this._textures.findIndex(info => info.id === texture);
    if (index < 0) return;
    this._glManager.getGl().deleteTexture(texture);
    this._textures.splice(index, 1);
  }

  public releaseTextureByFilePath(fileName: string): void {
    const index = this._textures.findIndex(info => info.fileName === fileName);
    if (index < 0) return;
    this._glManager.getGl().deleteTexture(this._textures[index].id);
    this._textures.splice(index, 1);
  }

  public setGlManager(glManager: LAppGlManager): void {
    this._glManager = glManager;
  }

  _textures: TextureInfo[];
  private _glManager: LAppGlManager;
}

export class TextureInfo {
  img: HTMLImageElement | null = null;
  id: WebGLTexture = null;
  width = 0;
  height = 0;
  usePremultply: boolean;
  fileName: string;
}

