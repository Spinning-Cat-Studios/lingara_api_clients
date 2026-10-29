package com.getlingara.kotlin.dialogue

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.Requests
import java.net.http.HttpHeaders
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What [Dialogues.exportDialogue] sends beyond the id (ADR 29.10.26c D6). Each is sent only when
 * set, so the default is the active revision with its layout and no query at all.
 *
 * @property revision `active` (the server's default) or `draft`
 * @property layout `keep` (the server's default) or `omit`, which drops every `x-` member and has
 *   its own `ETag`
 * @property ifNoneMatch the `etag` of the copy you hold, sent back exactly as received
 */
public data class ExportDialogueOptions(
    val revision: String? = null,
    val layout: String? = null,
    val ifNoneMatch: String? = null,
)

/** What [Dialogues.exportDialogue] answers: the document, or that the copy you hold is current. */
public sealed class ExportedDialogue {
    /** The revision the `etag` names. */
    public abstract val revision: Long

    /** The `ETag`, exactly as the server sent it. */
    public abstract val etag: String

    /**
     * A `200`: the `dialogue.v1` document as text, never parsed here. Its bytes are canonical and
     * `etag` is their SHA-256, so store [irJson] exactly as received.
     */
    public data class Body(
        override val revision: Long,
        override val etag: String,
        val irJson: String,
    ) : ExportedDialogue()

    /** A `304`: unchanged since the `etag` sent as `If-None-Match`, so keep the copy you hold. */
    public data class NotModified(
        override val revision: Long,
        override val etag: String,
    ) : ExportedDialogue()
}

private const val NOT_MODIFIED = 304

/** The export's one request, its `304` read here rather than raised, and its two headers required. */
internal suspend fun export(
    requests: Requests,
    path: String,
    options: ExportDialogueOptions,
): ApiResponse<ExportedDialogue> {
    // The spec's parameter order, `revision` then `layout`, which every library emits.
    val query = query("revision" to options.revision, "layout" to options.layout)
    val call = Call("GET", "$path/export$query", null, "application/json", true)
    options.ifNoneMatch?.let { call.headers = mapOf("If-None-Match" to it) }
    val (response, served) = requests.bytes(call, NOT_MODIFIED)
    val (revision, etag) = revisionAndEtag(response.headers())
    val result =
        if (response.statusCode() == NOT_MODIFIED) {
            ExportedDialogue.NotModified(revision, etag)
        } else {
            ExportedDialogue.Body(revision, etag, utf8(response.body()))
        }
    return ApiResponse(result, served)
}

/**
 * `Lingara-Dialogue-Revision` and `ETag`, which the server sends on a `200` and a `304` alike:
 * either missing, or a revision that is not a non-negative integer, is `malformed_response`.
 */
private fun revisionAndEtag(headers: HttpHeaders): Pair<Long, String> {
    val text = headers.firstValue("Lingara-Dialogue-Revision").orElse("")
    val revision = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    val etag = headers.firstValue("ETag").orElse(null)
    if (revision == null || etag == null) throw TransportException(TransportKind.MALFORMED_RESPONSE, null)
    return revision to etag
}

/** The body as text; bytes that are not UTF-8 are `malformed_response`, never replaced. */
private fun utf8(bytes: ByteArray): String =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw TransportException(TransportKind.MALFORMED_RESPONSE, e)
    }
