//! The client and its nine original operations (CONTRACT.md K1–K6; ADR
//! 29.9.26p D4). The events operations and helpers are `events/`'s (ADR
//! 30.9.26aa D10). Its options are `ClientBuilder`'s (`builder.rs`); auth,
//! retries and error mapping are the request pipeline's (`pipeline.rs`).

use std::fmt;
use std::ops::Deref;
use std::sync::Arc;
use std::time::Duration;

use crate::builder::ClientBuilder;
use crate::error::Error;
use crate::generated::streams::{CREATE_LESSON_PLAN, GENERATE_VOCABULARY, SEND_TUTOR_MESSAGE, STREAM_LESSON_PLAN};
use crate::models::{
    CreateLessonPlanEvent, GenerateVocabularyEvent, LessonPlan, LessonPlanCreateRequest, SendTutorMessageEvent, StreamLessonPlanEvent,
    TutorTurnRequest, Usage, VersionDetail, VersionList, VocabRequest,
};
use crate::pipeline::encode_segment;
use crate::retry::RetryPolicy;
use crate::stream::EventStream;
use crate::token::{ClientCredentials, TokenSource};
use crate::version::VersionObserver;

/// The Lingara API. Cheap to clone: clones share one connection pool and
/// one token cache.
#[derive(Clone)]
pub struct Client {
    pub(crate) inner: Arc<Inner>,
}

pub(crate) struct Inner {
    pub http: reqwest::Client,
    pub base_url: String,
    pub version: Option<String>,
    pub versions: VersionObserver,
    pub policy: RetryPolicy,
    pub idle: Duration,
    /// K5a's bound on consecutive failed tail opens.
    pub tail_max_failures: u32,
    pub user_agent: String,
    pub tokens: Option<Arc<dyn TokenSource>>,
    /// Kept beside `tokens` only so `Debug` can render the client id.
    pub credentials: Option<ClientCredentials>,
}

/// A JSON result, with the `Lingara-Version` echo beside it. Derefs to `T`.
#[derive(Clone, Debug)]
pub struct ApiResponse<T> {
    pub(crate) value: T,
    pub(crate) served_version: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn into_inner(self) -> T {
        self.value
    }

    /// The `Lingara-Version` the server answered under, if it said.
    pub fn served_version(&self) -> Option<&str> {
        self.served_version.as_deref()
    }
}

impl<T> Deref for ApiResponse<T> {
    type Target = T;

    fn deref(&self) -> &T {
        &self.value
    }
}

impl Client {
    pub fn builder() -> ClientBuilder {
        ClientBuilder::default()
    }

    pub(crate) fn from_inner(inner: Inner) -> Self {
        Self { inner: Arc::new(inner) }
    }

    pub async fn generate_vocabulary(&self, body: &VocabRequest) -> Result<EventStream<GenerateVocabularyEvent>, Error> {
        self.stream(&GENERATE_VOCABULARY, None, Some(body)).await
    }

    pub async fn create_lesson_plan(&self, body: &LessonPlanCreateRequest) -> Result<EventStream<CreateLessonPlanEvent>, Error> {
        self.stream(&CREATE_LESSON_PLAN, None, Some(body)).await
    }

    pub async fn stream_lesson_plan(&self, id: &str) -> Result<EventStream<StreamLessonPlanEvent>, Error> {
        self.stream(&STREAM_LESSON_PLAN, Some(id), None::<&()>).await
    }

    pub async fn send_tutor_message(&self, body: &TutorTurnRequest) -> Result<EventStream<SendTutorMessageEvent>, Error> {
        self.stream(&SEND_TUTOR_MESSAGE, None, Some(body)).await
    }

    pub async fn get_lesson_plan(&self, id: &str) -> Result<ApiResponse<LessonPlan>, Error> {
        self.json(&format!("/v1/lesson-plans/{}", encode_segment(id)), true).await
    }

    pub async fn get_usage(&self) -> Result<ApiResponse<Usage>, Error> {
        self.json("/v1/usage", true).await
    }

    /// The spec's schema for this document is a bare object.
    pub async fn get_open_api_document(&self) -> Result<ApiResponse<serde_json::Value>, Error> {
        self.json("/v1/openapi.json", false).await
    }

    pub async fn list_api_versions(&self) -> Result<ApiResponse<VersionList>, Error> {
        self.json("/v1/versions", false).await
    }

    pub async fn get_api_version(&self, id: &str) -> Result<ApiResponse<VersionDetail>, Error> {
        self.json(&format!("/v1/versions/{}", encode_segment(id)), false).await
    }
}

impl fmt::Debug for Client {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let inner = &self.inner;
        let custom_source = inner.tokens.is_some() && inner.credentials.is_none();
        f.debug_struct("Client")
            .field("base_url", &inner.base_url)
            .field("version", &inner.version)
            .field("credentials", &inner.credentials)
            .field("custom_token_source", &custom_source)
            .finish()
    }
}

#[cfg(test)]
#[path = "tests/client_tests.rs"]
mod tests;
