import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('Usage: node patch_sample.mjs <lappmodel.ts>');
let source = fs.readFileSync(target, 'utf8');

function replaceOnce(before, after, label) {
  const index = source.indexOf(before);
  if (index < 0) throw new Error(`Pinned sample changed; patch not found: ${label}`);
  if (source.indexOf(before, index + before.length) >= 0 && label.startsWith('unique:')) {
    throw new Error(`Pinned sample changed; patch is ambiguous: ${label}`);
  }
  source = source.slice(0, index) + after + source.slice(index + before.length);
}

replaceOnce(
  `  CompleteSetup\n}\n\n/**\n * ユーザーが実際に使用するモデルの実装クラス`,
  `  CompleteSetup\n}\n\nfunction reportStageStatus(message: string): void {\n  const android = (globalThis as unknown as {\n    AndroidStage?: { onStageStatus?: (value: string) => void };\n  }).AndroidStage;\n  android?.onStageStatus?.(message);\n}\n\nfunction reportStageError(message: string): void {\n  const android = (globalThis as unknown as {\n    AndroidStage?: { onStageError?: (value: string) => void };\n  }).AndroidStage;\n  android?.onStageError?.(message);\n}\n\n/**\n * ユーザーが実際に使用するモデルの実装クラス`,
  'unique: status helpers'
);

replaceOnce(
  `  public loadAssets(dir: string, fileName: string): void {\n    this._modelHomeDir = dir;\n\n    fetch(\`${'${this._modelHomeDir}${fileName}'}\`)`,
  `  public loadAssets(dir: string, fileName: string): void {\n    this._modelHomeDir = dir;\n    reportStageStatus('正在读取 model3 配置…');\n\n    fetch(\`${'${this._modelHomeDir}${fileName}'}\`)`,
  'unique: loadAssets status'
);

replaceOnce(
  `      .then(arrayBuffer => {\n        const setting: ICubismModelSetting = new CubismModelSettingJson(`,
  `      .then(arrayBuffer => {\n        reportStageStatus('model3 已读取，正在读取约139 MiB moc3…');\n        const setting: ICubismModelSetting = new CubismModelSettingJson(`,
  'model3 parsed status'
);

replaceOnce(
  `      .catch(error => {\n        // model3.json読み込みでエラーが発生した時点で描画は不可能なので、setupせずエラーをcatchして何もしない\n        CubismLogError(\`Failed to load file ${'${this._modelHomeDir}${fileName}'}\`);\n      });`,
  `      .catch(error => {\n        const detail = error instanceof Error ? error.message : String(error);\n        CubismLogError(\`Failed to load file ${'${this._modelHomeDir}${fileName}'}\`);\n        reportStageError(\`model3 读取失败：${'${detail}'}\`);\n      });`,
  'unique: model3 error'
);

replaceOnce(
  `        .then(arrayBuffer => {\n          this.loadModel(arrayBuffer, this._mocConsistency);\n          this._state = LoadStep.LoadExpression;`,
  `        .then(arrayBuffer => {\n          if (!arrayBuffer || arrayBuffer.byteLength === 0) {\n            throw new Error('moc3 文件为空或读取失败');\n          }\n          reportStageStatus(\`moc3 已读取 ${'${(arrayBuffer.byteLength / 1048576).toFixed(1)}'} MiB，正在创建 Cubism 5 模型…\`);\n          this.loadModel(arrayBuffer, this._mocConsistency);\n          reportStageStatus('Cubism 5 模型已创建，正在读取表情与物理…');\n          this._state = LoadStep.LoadExpression;`,
  'unique: moc progress'
);

const methodStart = source.indexOf('  private setupTextures(): void {');
const methodEndMarker = `\n  /**\n   * レンダラを再構築する`;
const methodEnd = source.indexOf(methodEndMarker, methodStart);
if (methodStart < 0 || methodEnd < 0) {
  throw new Error('Pinned sample changed; setupTextures not found');
}

const sequentialMethod = `  private setupTextures(): void {\n    const usePremultiply = true;\n    if (this._state !== LoadStep.LoadTexture) return;\n\n    const textureCount = this._modelSetting.getTextureCount();\n    this._state = LoadStep.WaitLoadTexture;\n    reportStageStatus(\`模型核心已创建，准备逐张加载 ${'${textureCount}'} 张原始2K贴图…\`);\n\n    const loadTexture = (modelTextureNumber: number): void => {\n      if (modelTextureNumber >= textureCount) {\n        this._state = LoadStep.CompleteSetup;\n        reportStageStatus('26张2K贴图已全部上传，正在完成首帧…');\n        return;\n      }\n\n      const relative = this._modelSetting.getTextureFileName(modelTextureNumber);\n      if (!relative) {\n        this._textureCount++;\n        setTimeout(() => loadTexture(modelTextureNumber + 1), 0);\n        return;\n      }\n\n      const texturePath = this._modelHomeDir + relative;\n      reportStageStatus(\`正在加载原始2K贴图 ${'${modelTextureNumber + 1}'}/${'${textureCount}'}…\`);\n      this._subdelegate.getTextureManager().createTextureFromPngFile(\n        texturePath,\n        usePremultiply,\n        (textureInfo: TextureInfo): void => {\n          this.getRenderer().bindTexture(modelTextureNumber, textureInfo.id);\n          this.getRenderer().setIsPremultipliedAlpha(usePremultiply);\n          this._textureCount++;\n          reportStageStatus(\`已上传原始2K贴图 ${'${this._textureCount}'}/${'${textureCount}'}\`);\n          setTimeout(() => loadTexture(modelTextureNumber + 1), 0);\n        },\n        (message: string): void => {\n          reportStageError(\`贴图 ${'${modelTextureNumber + 1}'}/${'${textureCount}'} 失败：${'${message}'}\`);\n        }\n      );\n    };\n\n    if (textureCount === 0) {\n      this._state = LoadStep.CompleteSetup;\n      reportStageStatus('模型没有贴图，正在完成首帧…');\n      return;\n    }\n    loadTexture(0);\n  }\n`;

source = source.slice(0, methodStart) + sequentialMethod + source.slice(methodEnd);
fs.writeFileSync(target, source);
