/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Punto de entrada mínimo para el entorno web de previsualización.
 * Muestra únicamente "Hello World" para mantener la compilación web limpia,
 * mientras el proyecto móvil nativo en Kotlin + Rust vive en la carpeta /android.
 */
export default function App() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-neutral-950 text-neutral-100">
      <h1 className="text-3xl font-semibold tracking-tight">Hello World</h1>
    </main>
  );
}
