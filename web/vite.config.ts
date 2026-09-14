import { defineConfig } from 'vite'
export default defineConfig({
 base: './',
 // Keep Vite's ?url asset imports out of dependency pre-bundling in the dev sandbox.
 optimizeDeps: {exclude: ['@tldraw/assets']},
 build: {outDir: '../android/app/src/main/assets/web', emptyOutDir: true},
})
