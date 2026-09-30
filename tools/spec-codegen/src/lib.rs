//! The generator view of the Lingara OpenAPI document (ADR 29.9.26m D4).
//!
//! The spec is OpenAPI 3.2 and no mainstream generator reads 3.2, so this
//! crate writes a view of it that one can: the four event streams lifted
//! into named, discriminated unions (`lift`), the rest downconverted to 3.1
//! (`downconvert`), and the same view again in 3.0.3 (`dialect30`). Each pass
//! refuses what it cannot say rather than approximating it. The view is a
//! generator input only: it is never served and never replaces the spec.

pub mod cli;
pub mod dialect30;
pub mod downconvert;
pub mod lift;
pub mod record;
pub mod walk;

#[cfg(test)]
mod cli_tests;
#[cfg(test)]
mod dialect30_tests;
#[cfg(test)]
mod downconvert_tests;
#[cfg(test)]
mod lift_tests;
#[cfg(test)]
mod record_tests;
#[cfg(test)]
mod view_tests;
#[cfg(test)]
mod walk_tests;

use serde_json::{json, Value};

/// Why the view could not be written. The message names the JSON pointer,
/// or the operation and branch, it is about.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refusal(pub String);

impl std::fmt::Display for Refusal {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// Both dialects of one view.
pub struct View {
    pub v31: Value,
    pub v30: Value,
}

/// The view of `spec`, naming `source` (the `spec/SOURCE` line) as its origin.
pub fn build_view(spec: &Value, source: &str) -> Result<View, Refusal> {
    let from = downconvert::check_version(spec)?;
    let mut doc = spec.clone();
    lift::lift(&mut doc)?;
    downconvert::downconvert(&mut doc)?;
    let info = doc
        .get_mut("info")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| Refusal("/info: missing".into()))?;
    info.insert("x-lingara-view".into(), json!({ "source": source, "from": from }));
    let v30 = dialect30::dialect30(&doc)?;
    Ok(View { v31: doc, v30 })
}

/// Pretty-printed with a trailing newline: the committed byte form.
pub fn render(v: &Value) -> String {
    let mut s = serde_json::to_string_pretty(v).expect("a Value always serialises");
    s.push('\n');
    s
}
