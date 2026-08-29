import { defineConfig, UserConfig, ConfigEnv } from 'vite';
import path from 'path';

export default defineConfig((env: ConfigEnv): UserConfig => ({
  root: './',
  base: './',
  publicDir: './public',
  resolve: {
    extensions: ['.ts', '.js'],
    alias: { '@framework': path.resolve(__dirname, '../../../Framework/src') }
  },
  build: {
    target: 'baseline-widely-available',
    assetsDir: 'assets',
    outDir: './dist',
    sourcemap: env.mode === 'development'
  }
}));

