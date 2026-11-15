//! Motor JavaScript embebido basado en QuickJS-NG (`rquickjs`, licencia MIT) para Aura Music.
//!
//! Cumple dos roles dentro del proyecto:
//! 1. Servir como runtime JavaScript ligero (vía binario `libaura_qjs.so` o JNI) para que
//!    `yt-dlp` + `yt-dlp-ejs` resuelvan desafíos JavaScript (EJS) sin Deno ni Node.js.
//! 2. Proveer evaluación JS aislada en Rust puro para pruebas y diagnósticos en 32 y 64 bits.

use std::sync::{Arc, Mutex};

use rquickjs::{CatchResultExt, Context, Function, Object, Runtime};

/// Versión reportada de QuickJS-NG compatible con la detección de `yt-dlp`.
pub const QUICKJS_VERSION_STRING: &str = crate::QUICKJS_VERSION_STRING;

/// Resultado estructurado de la ejecución de un script JavaScript en QuickJS-NG.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JsExecutionResult {
    pub stdout: String,
    pub stderr: String,
}

/// Evalúa un script JavaScript en un entorno aislado de QuickJS-NG con soporte de
/// `print`, `console.log`/`warn`/`error`, `scriptArgs` y utilidades mínimas de `std`/`os`.
pub fn eval_script_with_args(script: &str, args: &[String]) -> Result<JsExecutionResult, String> {
    let runtime = Runtime::new().map_err(|e| format!("No se pudo inicializar QuickJS-NG: {e}"))?;
    // Límite de memoria (128 MiB) y stack (2 MiB) para evitar bloqueos por scripts complejos.
    runtime.set_memory_limit(128 * 1024 * 1024);
    runtime.set_max_stack_size(2 * 1024 * 1024);

    let context =
        Context::full(&runtime).map_err(|e| format!("No se pudo crear contexto QuickJS: {e}"))?;

    let stdout_buf = Arc::new(Mutex::new(String::new()));
    let stderr_buf = Arc::new(Mutex::new(String::new()));

    let exec_result: Result<(), String> = context.with(|ctx| {
        let globals = ctx.globals();

        // `scriptArgs`: argumentos accesibles por scripts estilo qjs CLI.
        let js_args = rquickjs::Array::new(ctx.clone())
            .map_err(|e| format!("Error creando scriptArgs: {e}"))?;
        for (idx, arg) in args.iter().enumerate() {
            js_args
                .set(idx, arg.as_str())
                .map_err(|e| format!("Error asignando scriptArgs[{idx}]: {e}"))?;
        }
        globals
            .set("scriptArgs", js_args)
            .map_err(|e| format!("Error registrando scriptArgs: {e}"))?;

        // Función `print(...)` estilo QuickJS CLI.
        let print_fn = make_capture_fn(ctx.clone(), Arc::clone(&stdout_buf))?;
        globals
            .set("print", print_fn)
            .map_err(|e| format!("Error registrando print(): {e}"))?;

        // Objeto `console` (`log`, `info`, `warn`, `error`, `debug`).
        let console_obj =
            Object::new(ctx.clone()).map_err(|e| format!("Error creando objeto console: {e}"))?;

        let log_fn = make_capture_fn(ctx.clone(), Arc::clone(&stdout_buf))?;
        let warn_fn = make_capture_fn(ctx.clone(), Arc::clone(&stderr_buf))?;

        console_obj
            .set("log", log_fn.clone())
            .map_err(|e| e.to_string())?;
        console_obj
            .set("info", log_fn.clone())
            .map_err(|e| e.to_string())?;
        console_obj
            .set("debug", log_fn)
            .map_err(|e| e.to_string())?;
        console_obj
            .set("warn", warn_fn.clone())
            .map_err(|e| e.to_string())?;
        console_obj
            .set("error", warn_fn)
            .map_err(|e| e.to_string())?;
        globals
            .set("console", console_obj)
            .map_err(|e| format!("Error registrando console: {e}"))?;

        // Evalúa el script y captura excepciones JS detalladas (mensaje + stack).
        let eval_res = ctx.eval::<rquickjs::Value, _>(script).catch(&ctx);
        match eval_res {
            Ok(_) => Ok(()),
            Err(caught) => Err(format!("Excepción JavaScript: {caught:?}")),
        }
    });

    // Asegura drenar trabajos pendientes de promesas si el script usó microtasks.
    while runtime.is_job_pending() {
        if let Err(err) = runtime.execute_pending_job() {
            return Err(format!(
                "Error ejecutando promesa pendiente en QuickJS: {err:?}"
            ));
        }
    }

    exec_result?;

    let stdout = stdout_buf.lock().map(|g| g.clone()).unwrap_or_default();
    let stderr = stderr_buf.lock().map(|g| g.clone()).unwrap_or_default();

    Ok(JsExecutionResult { stdout, stderr })
}

/// Registra un callback variádico JS (`print` / `console.*`) compartiendo el mismo lifetime `'js`
/// entre `Ctx<'js>` y `Rest<Value<'js>>` para cumplir la invarianza exigida por `rquickjs`.
fn make_capture_fn<'js>(
    ctx: rquickjs::Ctx<'js>,
    target: Arc<Mutex<String>>,
) -> Result<Function<'js>, String> {
    Function::new(
        ctx,
        move |call_ctx: rquickjs::Ctx<'js>,
              args: rquickjs::function::Rest<rquickjs::Value<'js>>| {
            let line = format_js_args(&call_ctx, &args);
            if let Ok(mut buf) = target.lock() {
                if !buf.is_empty() {
                    buf.push('\n');
                }
                buf.push_str(&line);
            }
        },
    )
    .map_err(|e| format!("Error creando función de salida JS: {e}"))
}

fn format_js_args<'js>(
    ctx: &rquickjs::Ctx<'js>,
    args: &rquickjs::function::Rest<rquickjs::Value<'js>>,
) -> String {
    let mut parts = Vec::with_capacity(args.0.len());
    for val in &args.0 {
        let rendered = if let Some(s) = val.as_string() {
            s.to_string().unwrap_or_default()
        } else if val.is_undefined() {
            "undefined".to_string()
        } else if val.is_null() {
            "null".to_string()
        } else if let Ok(Some(json)) = ctx.json_stringify(val.clone()) {
            json.to_string().unwrap_or_default()
        } else {
            val.clone()
                .try_into_string()
                .ok()
                .and_then(|s| s.to_string().ok())
                .unwrap_or_else(|| format!("{val:?}"))
        };
        parts.push(rendered);
    }
    parts.join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evaluates_js_expressions_and_console_output() {
        let res = eval_script_with_args(
            "const x = 21 * 2; console.log('aura', x); print('ok');",
            &[],
        )
        .expect("QuickJS-NG debería evaluar correctamente");

        assert_eq!(res.stdout, "aura 42\nok");
        assert!(res.stderr.is_empty());
    }

    #[test]
    fn exposes_script_args_to_javascript() {
        let args = vec!["script.js".to_string(), "token123".to_string()];
        let res = eval_script_with_args("console.log(scriptArgs[1]);", &args)
            .expect("scriptArgs debería estar disponible");

        assert_eq!(res.stdout, "token123");
    }

    #[test]
    fn reports_javascript_runtime_errors_cleanly() {
        let err = eval_script_with_args("throw new Error('fallo_ejs');", &[])
            .expect_err("Debe devolver error en excepción JS");
        assert!(err.contains("Excepción JavaScript"));
    }
}
