use crate::control::LogEntry;
use crate::run::{Record, check_only, parse_results, verify};

fn line(case: &str, result: &str) -> String {
    format!(
        r#"{{"case":"{case}","lang":"rust","library_version":"0.1.0","result":"{result}","client_mismatches":[],"server_mismatches":[],"duration_ms":3}}"#
    )
}

fn record(expected: &[&str], armed: &[&str]) -> Record {
    let mut log = Vec::new();
    for id in armed {
        log.push(LogEntry::Arm(id.to_string()));
        log.push(LogEntry::Finish(id.to_string()));
    }
    Record { expected: expected.iter().map(|s| s.to_string()).collect(), log, stray: Vec::new() }
}

/// 29.9.26n AC12: `run` fails a results file that omits an armed case,
/// reports a case never armed, or holds a non-`pass` line.
#[test]
fn results_must_match_the_arm_log() {
    let both = record(&["k1.a", "k1.b"], &["k1.a", "k1.b"]);
    let clean = parse_results(&format!("{}\n{}\n", line("k1.a", "pass"), line("k1.b", "pass")));
    assert!(verify("rust", &clean, &both).is_empty());

    let omitted = parse_results(&line("k1.a", "pass"));
    assert_eq!(verify("rust", &omitted, &both), vec!["k1.b: missing from the results"]);

    let never_armed = record(&["k1.a", "k1.b"], &["k1.a"]);
    let problems = verify("rust", &clean, &never_armed);
    assert_eq!(problems, vec!["k1.b: reported but never armed"]);

    let failed = parse_results(&format!("{}\n{}\n", line("k1.a", "pass"), line("k1.b", "fail")));
    let problems = verify("rust", &failed, &both);
    assert_eq!(problems.len(), 1);
    assert!(problems[0].starts_with("k1.b: fail"), "{problems:?}");
}

#[test]
fn unfinished_duplicate_foreign_and_garbled_lines_fail() {
    let mut unfinished = record(&["k1.a"], &["k1.a"]);
    unfinished.log.push(LogEntry::Arm("k1.b".into()));
    unfinished.stray.push("GET /v1/usage: no case is armed".into());
    let text = format!("{}\n{}\nnot json\n", line("k1.a", "pass"), line("k1.a", "pass"));
    let problems = verify("rust", &parse_results(&text), &unfinished);
    let joined = problems.join("\n");
    for needle in ["stray request", "results: line 3", "k1.a: reported 2 times", "k1.b: armed but never finished"] {
        assert!(joined.contains(needle), "{needle} in {joined}");
    }
    let wrong_lang = verify("go", &parse_results(&line("k1.a", "pass")), &record(&["k1.a"], &["k1.a"]));
    assert_eq!(wrong_lang, vec!["k1.a: lang `rust` is not `go`"]);
}

/// 29.9.26n AC13: `LINGARA_CONFORMANCE_ONLY` is refused when `CI` is set.
#[test]
fn only_filter_refused_in_ci() {
    assert!(check_only(Some("k1.a"), Some("true")).is_err());
    assert!(check_only(Some("k1.a"), Some("1")).is_err());
    let local = check_only(Some("k1.a, k5.b"), None).unwrap().unwrap();
    assert_eq!(local.into_iter().collect::<Vec<_>>(), vec!["k1.a", "k5.b"]);
    assert_eq!(check_only(None, Some("true")), Ok(None));
    assert_eq!(check_only(Some(""), Some("true")), Ok(None));
}
