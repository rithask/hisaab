import { createHash } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

/**
 * Writes dist/sw.js (the service worker that makes the app installable and usable offline) from sw.template.js,
 * with the list of built files to keep on the device and a version that changes whenever they do.
 */
function serviceWorker(): Plugin {
  return {
    name: 'hisaab-sw',
    apply: 'build',
    generateBundle(_, bundle) {
      const built = Object.keys(bundle).filter((f) => f !== 'index.html' && !f.endsWith('.map'))
      const pub = readdirSync('public').filter((f) => !f.startsWith('.'))
      const shell = ['/', ...[...built, ...pub].sort().map((f) => '/' + f)]
      const version = createHash('sha256').update(shell.join('\n')).digest('hex').slice(0, 12)
      const source = readFileSync('sw.template.js', 'utf8').replaceAll('__VERSION__', version).replaceAll('__SHELL__', JSON.stringify(shell))
      if (!source.includes(`const VERSION = '${version}'`) || source.includes('__SHELL__')) this.error('sw.template.js: placeholders not filled in')
      this.emitFile({ type: 'asset', fileName: 'sw.js', source })
    },
  }
}

export default defineConfig({
  plugins: [react(), tailwindcss(), serviceWorker()],
  server: {
    host: true,
    port: 5173,
    proxy: { '/api': 'http://localhost:8080', '/oauth2': 'http://localhost:8080', '/login/oauth2': 'http://localhost:8080' },
  },
})
