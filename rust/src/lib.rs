//! The official Lingara API library for Rust.
//!
//! ```no_run
//! use std::num::NonZeroU8;
//! use lingara::{Client, models::{GenerateVocabularyEvent, VocabRequest}};
//!
//! # async fn run() -> Result<(), Box<dyn std::error::Error>> {
//! let client = Client::builder()
//!     .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
//!     .build()?;
//!
//! let request = VocabRequest { level: 2, source_lang: "en".into(), target_lang: "zh".into(), count: NonZeroU8::new(8) };
//! let mut stream = client.generate_vocabulary(&request).await?;
//! while let Some(event) = stream.next().await {
//!     if let GenerateVocabularyEvent::Item(item) = event? {
//!         println!("{} {}", item.word, item.translation);
//!     }
//! }
//! # Ok(())
//! # }
//! ```
//!
//! Every library keeps one contract, `conformance/CONTRACT.md` in the
//! repository: the token handling, retries, errors and streams below are
//! its K1–K6, and [`events`] holds its K5a tail, its event helpers and its
//! appendix W webhook verifier.

#[cfg(not(any(feature = "rustls", feature = "native-tls")))]
compile_error!("lingara needs a TLS backend: enable the `rustls` feature (the default) or the `native-tls` feature");

mod builder;
mod builder_options;
mod client;
mod error;
pub mod events;
mod generated;
pub mod models;
mod pipeline;
mod retry;
mod seams;
mod sse;
mod stream;
mod token;
mod version;

#[cfg(test)]
#[path = "tests/fake_server.rs"]
mod fake_server;

pub use builder::{BuildError, ClientBuilder, DEFAULT_BASE_URL, DEFAULT_TOKEN_URL};
pub use client::{ApiResponse, Client};
pub use error::{ApiError, Error, MaintenanceError, OAuthError, TransportError, TransportKind};
/// A boxed, `Send` future: what `TokenSource` and `Sleeper` return, so an
/// implementation needs no direct `futures` dependency.
pub use futures_util::future::BoxFuture;
/// Pin your client to this version: an event's `data` is rendered at your
/// client's pin, and this crate's models are this version's (ADR 30.9.26aa
/// D3).
pub use generated::spec_version::GENERATED_FOR_VERSION;
pub use seams::{Clock, Sleeper, SystemClock, TokioSleeper};
pub use stream::EventStream;
pub use token::{AccessToken, ClientCredentials, TokenAuth, TokenSource};
pub use version::{DeprecationNotice, Link};
