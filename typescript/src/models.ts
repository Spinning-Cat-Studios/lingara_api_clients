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
