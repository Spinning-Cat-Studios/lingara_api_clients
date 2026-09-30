//! K6's `<runtime>` (ADR 29.9.26p D7): the rustc version number and the
//! target triple, written as two `const`s for `User-Agent`.
//!
//! Only the version number is kept (`1.85.0`, `1.87.0-nightly`), never the
//! parenthesised commit hash and date `rustc --version` prints after it: C2
//! D8 admits no `)` in `<runtime>`. A value that is missing, or that holds a
//! byte outside visible ASCII or a `)`, becomes `unknown`.

use std::path::PathBuf;
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=RUSTC");
    let rustc = std::env::var("RUSTC").unwrap_or_else(|_| "rustc".to_owned());
    let version = Command::new(rustc)
        .arg("--version")
        .output()
        .ok()
        .and_then(|out| String::from_utf8(out.stdout).ok())
        .and_then(|text| text.split_whitespace().nth(1).map(str::to_owned));
    let target = std::env::var("TARGET").ok();
    let contents = format!(
        "const RUSTC_VERSION: &str = {:?};\nconst TARGET: &str = {:?};\n",
        clean(version),
        clean(target)
    );
    let out = PathBuf::from(std::env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("build_info.rs");
    std::fs::write(out, contents).expect("OUT_DIR is writable");
}

fn clean(value: Option<String>) -> String {
    match value {
        Some(v) if !v.is_empty() && v.bytes().all(|b| (0x21..=0x7e).contains(&b) && b != b')') => v,
        _ => "unknown".to_owned(),
    }
}
