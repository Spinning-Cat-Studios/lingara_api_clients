//! The API's types, generated from the spec (ADR 29.9.26p D2): every
//! request and response body, and the event enum each stream yields.
//!
//! Timestamps and ids are the server's strings, never `chrono` or `uuid`
//! types. An integer the spec bounds below by 1 is a `NonZero*` type, so
//! `VocabRequest.count` is `Option<NonZeroU8>`.

pub use crate::generated::models::*;
pub use crate::generated::streams::{CreateLessonPlanEvent, GenerateVocabularyEvent, SendTutorMessageEvent, StreamEventsEvent, StreamLessonPlanEvent};
