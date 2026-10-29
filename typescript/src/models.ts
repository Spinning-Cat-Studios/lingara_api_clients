// Short names for the generated types. Nothing here is hand-typed: every
// alias points into src/generated/schema.ts.

import type { components } from "./generated/schema.js";

type Schemas = components["schemas"];

// Request bodies
export type VocabRequest = Schemas["VocabRequest"];
export type LessonPlanCreateRequest = Schemas["LessonPlanCreateRequest"];
export type TutorTurnRequest = Schemas["TutorTurnRequest"];

// One discriminated union per stream, `event` the discriminant
export type GenerateVocabularyEvent = Schemas["GenerateVocabularyEvent"];
export type CreateLessonPlanEvent = Schemas["CreateLessonPlanEvent"];
export type StreamLessonPlanEvent = Schemas["StreamLessonPlanEvent"];
export type SendTutorMessageEvent = Schemas["SendTutorMessageEvent"];
export type StreamEventsEvent = Schemas["StreamEventsEvent"];

// Event payloads
export type VocabStarted = Schemas["VocabStarted"];
export type VocabItem = Schemas["VocabItem"];
export type VocabMeta = Schemas["VocabMeta"];
export type VocabExample = Schemas["VocabExample"];
export type PlanStarted = Schemas["PlanStarted"];
export type PlanPhase = Schemas["PlanPhase"];
export type PlanResult = Schemas["PlanResult"];
export type PlanPending = Schemas["PlanPending"];
export type TurnDelta = Schemas["TurnDelta"];
export type Notice = Schemas["Notice"];
export type StreamError = Schemas["StreamError"];

// JSON responses and their parts
export type LessonPlan = Schemas["LessonPlan"];
export type LessonPlanContent = Schemas["LessonPlanContent"];
export type PlanStatus = Schemas["PlanStatus"];
export type Usage = Schemas["Usage"];
export type AllowanceRow = Schemas["AllowanceRow"];
export type VersionList = Schemas["VersionList"];
export type VersionDetail = Schemas["VersionDetail"];
export type VersionSummary = Schemas["VersionSummary"];
export type VersionState = Schemas["VersionState"];

// Events (ADR 30.9.26aa): the feed's page, the raw envelope, the data
// models the Event arms carry, and sendEvent's inbound models and answer
export type EventEnvelope = Schemas["EventEnvelope"];
export type EventPage = Schemas["EventPage"];
export type LessonPlanReadyData = Schemas["LessonPlanReadyData"];
export type LessonPlanFailedData = Schemas["LessonPlanFailedData"];
export type UsageThresholdReachedData = Schemas["UsageThresholdReachedData"];
export type WebhookTestData = Schemas["WebhookTestData"];
export type AppInstalledData = Schemas["AppInstalledData"];
export type AppUninstalledData = Schemas["AppUninstalledData"];
export type AppDisabledData = Schemas["AppDisabledData"];
export type AppEnabledData = Schemas["AppEnabledData"];
export type PracticeCompletedData = Schemas["PracticeCompletedData"];
export type WorldContextChanged = Schemas["WorldContextChanged"];
export type WorldPracticeRequested = Schemas["WorldPracticeRequested"];
export type WorldPracticeCompleted = Schemas["WorldPracticeCompleted"];
export type PracticeItem = Schemas["PracticeItem"];
export type PracticeOutcome = Schemas["PracticeOutcome"];
export type Npc = Schemas["Npc"];
export type InboundEventAccepted = Schemas["InboundEventAccepted"];
export type ReactionReport = Schemas["ReactionReport"];

// Embedding (ADR 1.10.26w): the mint's request and its generated answer
// (createEmbedToken returns the hand-written MintedToken, never this), and
// the NPC turn's request, its window entries and its stream union
export type EmbedTokenRequest = Schemas["EmbedTokenRequest"];
export type EmbedToken = Schemas["EmbedToken"];
export type DialogueTurnRequest = Schemas["DialogueTurnRequest"];
export type DialogueEntry = Schemas["DialogueEntry"];
export type Speaker = Schemas["Speaker"];
export type SendDialogueTurnEvent = Schemas["SendDialogueTurnEvent"];

// A player's link to a Lingara account (ADR 22.10.26t): linkEmbedPlayer's
// answer, returned as generated since it carries no secret
export type EmbedPlayerLink = Schemas["EmbedPlayerLink"];
export type LinkStatus = Schemas["LinkStatus"];

// Canvases (ADR 16.10.26ak): the canvas and its revisions, its definition
// (`fields` and `params` stay open), the list page and its item, the dry-run
// and its verdicts, and the five request bodies
export type Canvas = Schemas["Canvas"];
export type CanvasDraft = Schemas["CanvasDraft"];
export type CanvasActive = Schemas["CanvasActive"];
export type CanvasDefinition = Schemas["CanvasDefinition"];
export type CanvasNode = Schemas["CanvasNode"];
export type CanvasNodeKind = Schemas["CanvasNodeKind"];
export type CanvasEdge = Schemas["CanvasEdge"];
export type CanvasParam = Schemas["CanvasParam"];
export type CanvasObjectRef = Schemas["CanvasObjectRef"];
export type CanvasPage = Schemas["CanvasPage"];
export type CanvasSummary = Schemas["CanvasSummary"];
export type CanvasMadeObject = Schemas["CanvasMadeObject"];
export type CanvasRecipe = Schemas["CanvasRecipe"];
export type CanvasDryRun = Schemas["CanvasDryRun"];
export type CanvasVerdict = Schemas["CanvasVerdict"];
export type CanvasVerdictKind = Schemas["CanvasVerdictKind"];
export type CanvasPlan = Schemas["CanvasPlan"];
export type CanvasActivationOutcome = Schemas["CanvasActivationOutcome"];
export type CreateCanvasRequest = Schemas["CreateCanvasRequest"];
export type RenameCanvasRequest = Schemas["RenameCanvasRequest"];
export type SaveCanvasDraftRequest = Schemas["SaveCanvasDraftRequest"];
export type ActivateCanvasRequest = Schemas["ActivateCanvasRequest"];

// Stored dialogues (ADR 29.10.26c): the dialogue and its revisions, its
// definition, its warnings, the list page and its item, and the four request
// bodies. The spec publishes `definition` as a bare `object` (its shape is the
// `dialogue.v1` schema's, never the spec's), which openapi-typescript reads as
// `Record<string, never>`; these aliases open it, so a document can be written
// and an unknown member read. The export's result is dialogue.ts's
// `ExportedDialogue`.
export type DialogueDefinition = Record<string, unknown>;
type WithDefinition<T> = Omit<T, "definition"> & { definition: DialogueDefinition };
export type DialogueDraft = WithDefinition<Schemas["DialogueDraft"]>;
export type DialogueActive = WithDefinition<Schemas["DialogueActive"]>;
export type Dialogue = Omit<Schemas["Dialogue"], "draft" | "active"> & { draft: DialogueDraft | null; active: DialogueActive | null };
export type DialogueWarning = Schemas["DialogueWarning"];
export type DialogueWarningCode = Schemas["DialogueWarningCode"];
export type DialogueRule = Schemas["DialogueRule"];
export type DialoguePage = Schemas["DialoguePage"];
export type DialogueSummary = Schemas["DialogueSummary"];
export type DialogueNpc = Schemas["DialogueNpc"];
export type CreateDialogueRequest = WithDefinition<Schemas["CreateDialogueRequest"]>;
export type RenameDialogueRequest = Schemas["RenameDialogueRequest"];
export type SaveDialogueDraftRequest = WithDefinition<Schemas["SaveDialogueDraftRequest"]>;
export type ActivateDialogueRequest = Schemas["ActivateDialogueRequest"];
