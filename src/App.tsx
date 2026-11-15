/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Portal web ligero de Aura Music para previsualizar y descargar directamente
 * desde el teléfono el logotipo oficial en formato PNG (512×512 y 1024×1024)
 * requerido por plataformas como Uptodown.
 */
export default function App() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-[#09070F] px-5 py-10 text-neutral-100">
      <section className="w-full max-w-md rounded-3xl border border-white/10 bg-white/[0.03] p-6 shadow-2xl">
        <div className="flex flex-col items-center text-center">
          <img
            src="/aura_music_logo_512.png"
            alt="Logotipo completo de Aura Music"
            className="h-52 w-52 rounded-3xl border border-white/10 shadow-lg"
          />
          <h1 className="mt-5 text-2xl font-semibold tracking-tight text-white">
            Aura Music — Logo Oficial
          </h1>
          <p className="mt-2 text-sm text-neutral-400">
            Descarga el logotipo completo en PNG para subirlo a Uptodown u otras tiendas de aplicaciones.
          </p>
        </div>

        <div className="mt-6 flex flex-col gap-3">
          <a
            href="/aura_music_logo_512.png"
            download="aura_music_logo_512.png"
            className="flex items-center justify-center text-center rounded-2xl bg-violet-500 px-4 py-3 text-sm font-semibold text-white transition hover:bg-violet-400"
          >
            Descargar PNG 512×512 (Recomendado Uptodown)
          </a>

          <a
            href="/aura_music_logo_1024.png"
            download="aura_music_logo_1024.png"
            className="flex items-center justify-center text-center rounded-2xl border border-white/15 bg-white/5 px-4 py-3 text-sm font-medium text-neutral-100 transition hover:bg-white/10"
          >
            Descargar PNG 1024×1024 (Alta resolución)
          </a>

          <a
            href="/aura_music_logo.svg"
            download="aura_music_logo.svg"
            className="flex items-center justify-center text-center rounded-2xl border border-white/10 px-4 py-2.5 text-xs font-medium text-neutral-400 transition hover:text-neutral-200"
          >
            Descargar vector original (.svg)
          </a>
        </div>
      </section>
    </main>
  );
}
