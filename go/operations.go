package lingara

import "context"

// The operations, thin: each takes its method and path from routes_gen.go, so
// a renamed path is a codegen diff rather than a silent break. A method's name
// is the operationId with its first letter upper-cased and Go's initialisms
// applied (ADR 29.9.26q D3). The three events operations, ListEvents,
// StreamEvents and SendEvent, live beside their helpers in events_*.go (ADR
// 30.9.26aa D10).

// GenerateVocabulary streams a vocabulary list (scope vocab:generate).
func (c *Client) GenerateVocabulary(ctx context.Context, body VocabRequest) (*Stream[GenerateVocabularyEvent], error) {
	return openStream(ctx, c, streamCall{operationID: "generateVocabulary", body: body}, decodeGenerateVocabularyEvent)
}

// CreateLessonPlan streams a new lesson plan's generation (scope
// lesson_plans:write). A plan served from the library is a lone result.
func (c *Client) CreateLessonPlan(ctx context.Context, body LessonPlanCreateRequest) (*Stream[CreateLessonPlanEvent], error) {
	return openStream(ctx, c, streamCall{operationID: "createLessonPlan", body: body}, decodeCreateLessonPlanEvent)
}

// StreamLessonPlan rejoins a lesson plan's generation by its id (scope
// lesson_plans:read).
func (c *Client) StreamLessonPlan(ctx context.Context, id string) (*Stream[StreamLessonPlanEvent], error) {
	return openStream(ctx, c, streamCall{operationID: "streamLessonPlan", id: id}, decodeStreamLessonPlanEvent)
}

// SendTutorMessage streams the tutor's reply to one turn (scope
// tutor:converse).
func (c *Client) SendTutorMessage(ctx context.Context, body TutorTurnRequest) (*Stream[SendTutorMessageEvent], error) {
	return openStream(ctx, c, streamCall{operationID: "sendTutorMessage", body: body}, decodeSendTutorMessageEvent)
}

// GetLessonPlan fetches a lesson plan by its id (scope lesson_plans:read).
func (c *Client) GetLessonPlan(ctx context.Context, id string) (*Result[LessonPlan], error) {
	return getJSON[LessonPlan](ctx, c, "getLessonPlan", id)
}

// GetUsage reports this client's allowance, or its ledger if it is metered
// (scope usage:read).
func (c *Client) GetUsage(ctx context.Context) (*Result[Usage], error) {
	return getJSON[Usage](ctx, c, "getUsage", "")
}

// GetOpenAPIDocument fetches the API's OpenAPI document. It needs no token.
func (c *Client) GetOpenAPIDocument(ctx context.Context) (*Result[map[string]any], error) {
	return getJSON[map[string]any](ctx, c, "getOpenApiDocument", "")
}

// GetAsyncAPIDocument fetches the AsyncAPI document that describes the API's
// events. It needs no token.
func (c *Client) GetAsyncAPIDocument(ctx context.Context) (*Result[map[string]any], error) {
	return getJSON[map[string]any](ctx, c, "getAsyncApiDocument", "")
}

// ListAPIVersions lists the API's versions. It needs no token.
func (c *Client) ListAPIVersions(ctx context.Context) (*Result[VersionList], error) {
	return getJSON[VersionList](ctx, c, "listApiVersions", "")
}

// GetAPIVersion describes one API version by its id. It needs no token.
func (c *Client) GetAPIVersion(ctx context.Context, id string) (*Result[VersionDetail], error) {
	return getJSON[VersionDetail](ctx, c, "getApiVersion", id)
}
