package lingara

import (
	"context"
	"io"
	"iter"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// Dialogues (ADR 29.10.26c): the seven /v1/dialogues operations and the page
// helper, on a group reached as client.Dialogues(). The group holds no state
// of its own: every call goes through the client's request path, so the
// token, retries, version pin and errors are the client's.
//
// A stale draft_version is 409 dialogue_conflict, returned as an *APIError
// and never retried: re-read with GetDialogue, take Draft.DraftVersion (or
// send 0 when Draft is nil), and decide. Branch on an error's Code, never its
// localised Message. The export is read by hand: its body is canonical bytes
// never parsed here, its two headers are part of the answer, and a 304 is an
// answer. There is no delete: a dialogue is deleted through the platform's
// lifecycle routes, not this library.

// DialogueFilters is what ListDialogues filters by, and what PageDialogues
// resends on every page. A zero field is not sent.
type DialogueFilters struct {
	// Limit is the page size, 1 to 50.
	Limit int
	// Name is a substring of the dialogue name.
	Name string
}

// ListDialoguesOptions is ListDialogues' query: the filters and the page to
// start from.
type ListDialoguesOptions struct {
	// Cursor is a previous page's NextCursor.
	Cursor  string
	Filters DialogueFilters
}

// ExportDialogueOptions is what ExportDialogue sends beyond the id. A zero
// field is not sent, so the default is the active revision with its layout.
type ExportDialogueOptions struct {
	// Revision is ExportDialogueParamsRevisionActive (the server's default)
	// or …Draft.
	Revision ExportDialogueParamsRevision
	// Layout is ExportDialogueParamsLayoutKeep (the server's default) or
	// …Omit, which drops every x- member and has its own ETag.
	Layout ExportDialogueParamsLayout
	// IfNoneMatch is the ETag of the copy you hold, sent back exactly as
	// received.
	IfNoneMatch string
}

// ExportedDialogue is what ExportDialogue answers: an
// ExportedDialogueBody on a 200, or an ExportedDialogueNotModified on a
// 304. Switch on its type.
type ExportedDialogue interface {
	exportedDialogue()
}

// ExportedDialogueBody is the dialogue.v1 document, unparsed: its bytes are
// canonical, and ETag is their SHA-256. Store IRJSON exactly as received.
type ExportedDialogueBody struct {
	Revision int64
	ETag     string
	IRJSON   string
}

// ExportedDialogueNotModified says the revision is unchanged since the ETag
// sent as IfNoneMatch: keep the copy you hold.
type ExportedDialogueNotModified struct {
	Revision int64
	ETag     string
}

func (ExportedDialogueBody) exportedDialogue()        {}
func (ExportedDialogueNotModified) exportedDialogue() {}

// Dialogues is the dialogue operations (scopes dialogues:read,
// dialogues:write).
type Dialogues struct {
	c *Client
}

// Dialogues returns the dialogue operations, over this client.
func (c *Client) Dialogues() *Dialogues { return &Dialogues{c: c} }

// ListDialogues fetches one page of dialogues; PageDialogues walks them.
func (g *Dialogues) ListDialogues(ctx context.Context, params ListDialoguesOptions) (*Result[DialoguePage], error) {
	rt := routes["listDialogues"]
	u := withQuery(g.c.url(rt.path, ""), dialogueQuery(params))
	return sendJSON[DialoguePage](ctx, g.c, request{method: rt.method, url: u, accept: "application/json", needsToken: rt.needsToken})
}

// CreateDialogue creates a dialogue (201). Its Warnings are advice, never a
// refusal.
func (g *Dialogues) CreateDialogue(ctx context.Context, body CreateDialogueRequest) (*Result[Dialogue], error) {
	return bodyCall[Dialogue](ctx, g.c, "createDialogue", "", body)
}

// GetDialogue reads one dialogue, its draft and active revision inline;
// either may be nil.
func (g *Dialogues) GetDialogue(ctx context.Context, dialogueID string) (*Result[Dialogue], error) {
	return getJSON[Dialogue](ctx, g.c, "getDialogue", dialogueID)
}

// RenameDialogue renames a dialogue (PATCH).
func (g *Dialogues) RenameDialogue(ctx context.Context, dialogueID string, body RenameDialogueRequest) (*Result[Dialogue], error) {
	return bodyCall[Dialogue](ctx, g.c, "renameDialogue", dialogueID, body)
}

// SaveDialogueDraft saves the draft (PUT); DraftVersion 0 with no draft
// opens one. A stale DraftVersion is 409 dialogue_conflict, which may follow
// your own retried save: re-read with GetDialogue and compare before you
// decide.
func (g *Dialogues) SaveDialogueDraft(ctx context.Context, dialogueID string, body SaveDialogueDraftRequest) (*Result[Dialogue], error) {
	return bodyCall[Dialogue](ctx, g.c, "saveDialogueDraft", dialogueID, body)
}

// ActivateDialogue promotes the draft. A stale or missing draft is 409
// dialogue_conflict and writes nothing.
func (g *Dialogues) ActivateDialogue(ctx context.Context, dialogueID string, body ActivateDialogueRequest) (*Result[Dialogue], error) {
	return bodyCall[Dialogue](ctx, g.c, "activateDialogue", dialogueID, body)
}

// ExportDialogue exports a dialogue (scope dialogues:read, which an embed
// token carries for the active revision). Pass the ETag you hold as
// IfNoneMatch, and an unchanged revision answers ExportedDialogueNotModified
// with no body; a 304 still spends one unit of the rate budget. A missing
// ETag or Lingara-Dialogue-Revision, or a revision that is not a
// non-negative integer, is a *TransportError of kind malformed_response.
// K4's retries apply: the export only reads.
func (g *Dialogues) ExportDialogue(ctx context.Context, dialogueID string, options ExportDialogueOptions) (*Result[ExportedDialogue], error) {
	rt := routes["exportDialogue"]
	r := request{
		method: rt.method, url: g.c.url(rt.path, dialogueID) + exportQuery(options), accept: "application/json",
		needsToken: rt.needsToken, allow: http.StatusNotModified,
	}
	if options.IfNoneMatch != "" {
		r.header = http.Header{"If-None-Match": {options.IfNoneMatch}}
	}
	res, err := g.c.send(ctx, ctx, r)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	served := g.c.versions.observe(res.Header, res.Request.URL)
	revision, etag, ok := revisionAndETag(res.Header)
	if !ok {
		return nil, &TransportError{Kind: MalformedResponse}
	}
	if res.StatusCode == http.StatusNotModified {
		return &Result[ExportedDialogue]{Value: ExportedDialogueNotModified{Revision: revision, ETag: etag}, ServedVersion: served}, nil
	}
	// Read as text and never decoded, so the bytes held are the bytes the
	// ETag names.
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, transportError(ctx, err, true)
	}
	value := ExportedDialogueBody{Revision: revision, ETag: etag, IRJSON: string(body)}
	return &Result[ExportedDialogue]{Value: value, ServedVersion: served}, nil
}

// PageDialogues yields every dialogue the filters match, following
// NextCursor until a page says has_more false, the filters resent on every
// page. It never sleeps or polls. The last pair is (zero, err) when a page
// fails; a page with has_more and no next_cursor is a *TransportError of
// kind malformed_response, its items unyielded.
func (g *Dialogues) PageDialogues(ctx context.Context, filters DialogueFilters) iter.Seq2[DialogueSummary, error] {
	return func(yield func(DialogueSummary, error) bool) {
		params := ListDialoguesOptions{Filters: filters}
		for {
			res, err := g.ListDialogues(ctx, params)
			if err == nil && res.Value.HasMore && res.Value.NextCursor == nil {
				err = &TransportError{Kind: MalformedResponse}
			}
			if err != nil {
				yield(DialogueSummary{}, err)
				return
			}
			for _, item := range res.Value.Items {
				if !yield(item, nil) {
					return
				}
			}
			if !res.Value.HasMore {
				return
			}
			params.Cursor = *res.Value.NextCursor
		}
	}
}

// dialogueQuery is ListDialogues' query string.
func dialogueQuery(params ListDialoguesOptions) url.Values {
	q := url.Values{}
	if params.Cursor != "" {
		q.Set("cursor", params.Cursor)
	}
	if params.Filters.Name != "" {
		q.Set("name", params.Filters.Name)
	}
	if params.Filters.Limit != 0 {
		q.Set("limit", strconv.Itoa(params.Filters.Limit))
	}
	return q
}

// exportQuery is the export's query, "" when no option is given, else
// "?revision=…&layout=…" in the spec's parameter order (url.Values would
// sort it).
func exportQuery(options ExportDialogueOptions) string {
	var parts []string
	if options.Revision != "" {
		parts = append(parts, "revision="+url.QueryEscape(string(options.Revision)))
	}
	if options.Layout != "" {
		parts = append(parts, "layout="+url.QueryEscape(string(options.Layout)))
	}
	if len(parts) == 0 {
		return ""
	}
	return "?" + strings.Join(parts, "&")
}

// revisionAndETag reads Lingara-Dialogue-Revision and ETag, which the server
// sends on a 200 and a 304 alike. Either missing, or a revision that is not
// a non-negative integer, is not ok.
func revisionAndETag(h http.Header) (int64, string, bool) {
	etag := h.Get("ETag")
	revision, err := strconv.ParseUint(h.Get("Lingara-Dialogue-Revision"), 10, 63)
	if err != nil || etag == "" {
		return 0, "", false
	}
	return int64(revision), etag, true
}
