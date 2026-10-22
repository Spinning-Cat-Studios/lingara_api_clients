//! ADR 22.10.26r W10: a `kind = "line"` version file, the version carried as
//! source (`VERSION = "0.0.0"` in a gem, `public const VERSION = '0.0.0';` in
//! a PHAR). Each key is a line prefix, matched after a line's leading
//! whitespace and ending in the value's opening quote; the value runs to the
//! next occurrence of that quote on the line. Prefixes, never regexes: one
//! cannot match more than its author meant.

use std::ops::Range;

use crate::manifest::VersionFile;

/// Each key's value. The first key that fails is the file's one error.
pub fn read_keys(text: &str, file: &VersionFile) -> Result<Vec<Option<String>>, String> {
    file.keys.iter().map(|key| Ok(Some(text[value_range(text, &file.path, key)?].to_string()))).collect()
}

/// `text` with each key's value replaced by `to`, every other byte kept, the
/// line's indentation and the file's line endings included.
pub fn rewrite(text: &str, file: &VersionFile, to: &str) -> Result<String, String> {
    let mut out = text.to_string();
    for key in &file.keys {
        let range = value_range(&out, &file.path, key)?;
        out.replace_range(range, to);
    }
    Ok(out)
}

/// The byte range of the value `key` opens: exactly one line may start with
/// it, and that line must close the quote.
fn value_range(text: &str, path: &str, key: &str) -> Result<Range<usize>, String> {
    let fail = |why: &str| format!("{path}: line prefix `{key}` {why}");
    let Some(quote) = key.chars().last().filter(|c| *c == '"' || *c == '\'') else {
        return Err(fail("does not end in \" or '"));
    };
    // (where the value starts, where its line's content ends), per matching line.
    let mut hits = Vec::new();
    let mut offset = 0;
    for line in text.split_inclusive('\n') {
        let trimmed = line.trim_start();
        if trimmed.starts_with(key) {
            hits.push((offset + line.len() - trimmed.len() + key.len(), offset + line.trim_end_matches(['\n', '\r']).len()));
        }
        offset += line.len();
    }
    let [(start, end)] = hits.as_slice() else {
        return Err(fail(if hits.is_empty() { "matches no line" } else { "matches more than one line" }));
    };
    let close = text[*start..*end].find(quote).ok_or_else(|| fail("has no closing quote on its line"))?;
    Ok(*start..*start + close)
}
