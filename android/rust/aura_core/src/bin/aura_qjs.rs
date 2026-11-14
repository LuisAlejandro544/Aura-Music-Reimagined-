//! Ejecutable nativo `aura_qjs` (empaquetado en Android como `libaura_qjs.so` dentro de jniLibs).
//!
//! Implementa la interfaz de línea de comandos compatible con `yt-dlp --js-runtimes quickjs:<ruta>`:
//! - `--version` / `-V`: imprime la versión de QuickJS-NG.
//! - `--script <ruta>` o `<ruta>`: ejecuta un archivo JS y muestra su salida estándar.
//! - `-e <codigo>` / `--eval <codigo>`: evalúa una expresión JS en línea.
//! - `-`: lee y ejecuta código JavaScript desde stdin.

use std::fs;
use std::io::{self, Read};
use std::process::ExitCode;

use aura_core::js_runtime::{QUICKJS_VERSION_STRING, eval_script_with_args};

fn main() -> ExitCode {
    let raw_args: Vec<String> = std::env::args().skip(1).collect();
    if raw_args.is_empty() {
        eprintln!("Uso: aura_qjs [--version | --script <archivo.js> | -e <codigo> | <archivo.js>]");
        return ExitCode::from(2);
    }

    let mut script_path: Option<String> = None;
    let mut eval_code: Option<String> = None;
    let mut read_stdin = false;
    let mut script_args: Vec<String> = Vec::new();

    let mut i = 0;
    while i < raw_args.len() {
        let arg = &raw_args[i];
        match arg.as_str() {
            "--version" | "-V" => {
                println!("{QUICKJS_VERSION_STRING}");
                return ExitCode::SUCCESS;
            }
            "--script" => {
                i += 1;
                if i >= raw_args.len() {
                    eprintln!("Falta la ruta del archivo después de --script");
                    return ExitCode::from(2);
                }
                script_path = Some(raw_args[i].clone());
                script_args.push(raw_args[i].clone());
                script_args.extend_from_slice(&raw_args[i + 1..]);
                break;
            }
            "-e" | "--eval" => {
                i += 1;
                if i >= raw_args.len() {
                    eprintln!("Falta el código JavaScript después de {arg}");
                    return ExitCode::from(2);
                }
                eval_code = Some(raw_args[i].clone());
                script_args.extend_from_slice(&raw_args[i + 1..]);
                break;
            }
            "--std" | "--module" | "-m" | "-q" | "--quit" => {
                // Flags informativos aceptados para compatibilidad con invocaciones estilo qjs.
            }
            "-" => {
                read_stdin = true;
                script_args.push("-".to_string());
                script_args.extend_from_slice(&raw_args[i + 1..]);
                break;
            }
            "--" => {
                if i + 1 < raw_args.len() {
                    script_path = Some(raw_args[i + 1].clone());
                    script_args.extend_from_slice(&raw_args[i + 1..]);
                }
                break;
            }
            other if !other.starts_with('-') => {
                script_path = Some(other.to_string());
                script_args.extend_from_slice(&raw_args[i..]);
                break;
            }
            _ => {}
        }
        i += 1;
    }

    let code = if let Some(inline) = eval_code {
        inline
    } else if read_stdin {
        let mut buf = String::new();
        if let Err(err) = io::stdin().read_to_string(&mut buf) {
            eprintln!("Error leyendo JavaScript desde stdin: {err}");
            return ExitCode::from(1);
        }
        buf
    } else if let Some(path) = script_path {
        match fs::read_to_string(&path) {
            Ok(content) => content,
            Err(err) => {
                eprintln!("No se pudo leer el archivo JS '{path}': {err}");
                return ExitCode::from(1);
            }
        }
    } else {
        let mut buf = String::new();
        if let Err(err) = io::stdin().read_to_string(&mut buf) {
            eprintln!("Error leyendo stdin: {err}");
            return ExitCode::from(1);
        }
        buf
    };

    match eval_script_with_args(&code, &script_args) {
        Ok(res) => {
            if !res.stdout.is_empty() {
                println!("{}", res.stdout);
            }
            if !res.stderr.is_empty() {
                eprintln!("{}", res.stderr);
            }
            ExitCode::SUCCESS
        }
        Err(err) => {
            eprintln!("{err}");
            ExitCode::from(1)
        }
    }
}
