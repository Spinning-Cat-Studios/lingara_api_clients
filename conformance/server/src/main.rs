//! The Lingara client conformance server (ADR 29.9.26n D12, D14).
//!
//! `serve` replays the cases under `conformance/cases/` and checks what a
//! library sent; `run` drives one language's harness against it; and
//! `check-coverage` guards the case inventory against the generator view.

mod case;
mod control;
mod coverage;
mod http;
mod matcher;
mod replay;
mod run;
mod serve;

#[cfg(test)]
mod control_tests;
#[cfg(test)]
mod coverage_tests;
#[cfg(test)]
mod http_tests;
#[cfg(test)]
mod matcher_tests;
#[cfg(test)]
mod replay_tests;
#[cfg(test)]
mod run_tests;
#[cfg(test)]
mod test_support;

use std::path::PathBuf;

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "conformance-server", about = "Replays the Lingara client conformance cases")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Serve the replay and control surfaces on 127.0.0.1.
    Serve {
        #[arg(long, default_value = "conformance/cases")]
        cases: PathBuf,
        /// `0` picks a free port; the first stdout line is `listening <port>`.
        #[arg(long, default_value_t = 0)]
        port: u16,
    },
    /// Run one language's harness against every case.
    Run {
        #[arg(long)]
        lang: String,
        #[arg(long, default_value = "conformance/cases")]
        cases: PathBuf,
        /// The harness command line.
        #[arg(last = true)]
        command: Vec<String>,
    },
    /// Fail when an operation or behaviour has no case, or a case is stale.
    CheckCoverage {
        #[arg(long)]
        view: PathBuf,
        #[arg(long)]
        cases: PathBuf,
    },
}

fn main() {
    let code = match Cli::parse().command {
        Command::Serve { cases, port } => serve_forever(&cases, port),
        Command::Run { lang, cases, command } => run::run(&lang, &cases, &command),
        Command::CheckCoverage { view, cases } => coverage::run(&view, &cases),
    };
    std::process::exit(code);
}

fn serve_forever(cases_dir: &std::path::Path, port: u16) -> i32 {
    let (cases, errors) = case::load_dir(cases_dir);
    if !errors.is_empty() {
        errors.iter().for_each(|e| eprintln!("✗ {e}"));
        return 1;
    }
    let runtime = match tokio::runtime::Runtime::new() {
        Ok(runtime) => runtime,
        Err(e) => {
            eprintln!("✗ {e}");
            return 1;
        }
    };
    runtime.block_on(async {
        match serve::start(control::Shared::new(cases), port).await {
            Ok((port, server)) => {
                println!("listening {port}");
                let _ = server.await;
                0
            }
            Err(e) => {
                eprintln!("✗ cannot listen: {e}");
                1
            }
        }
    })
}
