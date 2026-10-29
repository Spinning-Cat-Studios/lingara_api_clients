<?php

declare(strict_types=1);

namespace Lingara\Dialogue;

/**
 * What Dialogues::exportDialogue() answers (ADR 29.10.26c D6): a Body (200),
 * the dialogue.v1 document as unparsed bytes with its revision and ETag; or
 * NotModified (304), when the ETag you sent still names the revision: keep
 * the copy you hold. Both arms carry `revision`, `etag` and `servedVersion`.
 */
interface ExportedDialogue {}
