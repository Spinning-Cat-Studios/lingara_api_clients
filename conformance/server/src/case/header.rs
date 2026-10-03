//! Header matchers (ADR 29.9.26n D10), split from `mod.rs` for its budget.
//! `same_as` is ADR 30.9.26aa D9's.

use serde::Deserialize;

/// One header matcher. Written as a one-key map (`{ prefix: … }`); read
/// through `HeaderMatchSpec` because the YAML deserialiser spells an
/// externally tagged enum as a `!tag`, not a map.
#[derive(Debug, Clone, Deserialize)]
#[serde(try_from = "HeaderMatchSpec")]
pub enum HeaderMatch {
    Equals(String),
    Prefix(String),
    Contains(String),
    Pattern(String),
    Absent(bool),
    Basic([String; 2]),
    /// This header equals the one an earlier exchange item's request
    /// carried (ADR 30.9.26aa D9): "the same key on every attempt".
    SameAs(SameAs),
}

/// `same_as: { request: <n>, header: <name> }`: `request` is the 0-based
/// index of an earlier item in `exchanges.items`, read as its first match.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SameAs {
    pub request: usize,
    pub header: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HeaderMatchSpec {
    equals: Option<String>,
    prefix: Option<String>,
    contains: Option<String>,
    pattern: Option<String>,
    absent: Option<bool>,
    basic: Option<[String; 2]>,
    same_as: Option<SameAs>,
}

impl TryFrom<HeaderMatchSpec> for HeaderMatch {
    type Error = String;

    fn try_from(spec: HeaderMatchSpec) -> Result<Self, String> {
        let all = [
            spec.equals.map(HeaderMatch::Equals),
            spec.prefix.map(HeaderMatch::Prefix),
            spec.contains.map(HeaderMatch::Contains),
            spec.pattern.map(HeaderMatch::Pattern),
            spec.absent.map(HeaderMatch::Absent),
            spec.basic.map(HeaderMatch::Basic),
            spec.same_as.map(HeaderMatch::SameAs),
        ];
        let mut set = all.into_iter().flatten();
        match (set.next(), set.next()) {
            (Some(one), None) => Ok(one),
            _ => Err("a matcher is exactly one of equals, prefix, contains, pattern, absent, basic, same_as".into()),
        }
    }
}
