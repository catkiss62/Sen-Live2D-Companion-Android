const fs = require('fs');
const path = require('path');

const root = __dirname;
const publicDir = path.join(root, 'public');
const shaderSource = path.resolve(root, '../../../Framework/Shaders');
const shaderTarget = path.join(publicDir, 'Framework/Shaders');
const resources = path.join(publicDir, 'Resources');

fs.rmSync(publicDir, { recursive: true, force: true });
fs.mkdirSync(resources, { recursive: true });
fs.mkdirSync(path.dirname(shaderTarget), { recursive: true });
fs.cpSync(shaderSource, shaderTarget, { recursive: true });

// Official sample view expects background/gear PNGs. Transparent one-pixel
// placeholders keep that code path valid without bundling sample models/assets.
const transparentPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL7WQAAAABJRU5ErkJggg==',
  'base64'
);
for (const name of ['back_class_normal.png', 'icon_gear.png', 'CloseNormal.png']) {
  fs.writeFileSync(path.join(resources, name), transparentPng);
}

