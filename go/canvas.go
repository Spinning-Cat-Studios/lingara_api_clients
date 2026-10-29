package lingara

import (
	"context"
	"encoding/json"
	"fmt"
	"iter"
	"net/url"
	"strconv"
	"strings"
)

// Canvases (ADR 16.10.26ak): the seven /v1/canvases operations and the page
// helper, on a group reached as client.Canvases(). The group holds no state
// of its own: every call goes through the client's request path, so the
// token, retries, version pin and errors are the client's.
//
// A stale draft_version is 409 canvas_conflict, returned as an *APIError and
// never retried: re-read with GetCanvas, compare drafts, and decide. Branch
// on an error's Code, never its localised Message. A dry-run answers 200
// whether or not Ok is true; a refused verdict's Code and English Error are
// data. There is no delete: a canvas is deleted through the platform's
// lifecycle routes, not this library.

// CanvasFilters is what ListCanvases filters by, and what PageCanvases
// resends on every page. A zero field is not sent; each list is sent
// comma-separated as one value.
type CanvasFilters struct {
	// Limit is the page size, 1 to 50.
	Limit int
	// Name is a substring of the canvas name.
	Name string
	// Status is ListCanvasesParamsStatusAll, …Live, …Changed or …Unapplied.
	Status  ListCanvasesParamsStatus
	Recipes []string
	Kinds   []string
	Scopes  []string
}

// ListCanvasesOptions is ListCanvases' query: the filters and the page to
// start from.
type ListCanvasesOptions struct {
	// Cursor is a previous page's NextCursor.
	Cursor  string
	Filters CanvasFilters
}

// Canvases is the canvas operations (scopes canvas:read, canvas:write).
type Canvases struct {
	c *Client
}

// Canvases returns the canvas operations, over this client.
func (c *Client) Canvases() *Canvases { return &Canvases{c: c} }

// ListCanvases fetches one page of canvases; PageCanvases walks them.
func (g *Canvases) ListCanvases(ctx context.Context, params ListCanvasesOptions) (*Result[CanvasPage], error) {
	rt := routes["listCanvases"]
	u := withQuery(g.c.url(rt.path, ""), canvasQuery(params))
	return sendJSON[CanvasPage](ctx, g.c, request{method: rt.method, url: u, accept: "application/json", needsToken: rt.needsToken})
}

// CreateCanvas creates a canvas (201). The answer does not echo Recipe.
func (g *Canvases) CreateCanvas(ctx context.Context, body CreateCanvasRequest) (*Result[Canvas], error) {
	return bodyCall[Canvas](ctx, g.c, "createCanvas", "", body)
}

// GetCanvas reads one canvas, its draft and active revision inline.
func (g *Canvases) GetCanvas(ctx context.Context, canvasID string) (*Result[Canvas], error) {
	return getJSON[Canvas](ctx, g.c, "getCanvas", canvasID)
}

// RenameCanvas renames a canvas (PATCH).
func (g *Canvases) RenameCanvas(ctx context.Context, canvasID string, body RenameCanvasRequest) (*Result[Canvas], error) {
	return bodyCall[Canvas](ctx, g.c, "renameCanvas", canvasID, body)
}

// SaveCanvasDraft saves the draft (PUT). A stale DraftVersion is 409
// canvas_conflict, which may follow your own retried save: re-read with
// GetCanvas and compare before you decide.
func (g *Canvases) SaveCanvasDraft(ctx context.Context, canvasID string, body SaveCanvasDraftRequest) (*Result[Canvas], error) {
	return bodyCall[Canvas](ctx, g.c, "saveCanvasDraft", canvasID, body)
}

// ActivateCanvas promotes a draft with the objects an apply made. A replay
// at a draft_version already promoted is 409 canvas_conflict and writes
// nothing.
func (g *Canvases) ActivateCanvas(ctx context.Context, canvasID string, body ActivateCanvasRequest) (*Result[Canvas], error) {
	return bodyCall[Canvas](ctx, g.c, "activateCanvas", canvasID, body)
}

// DryRunCanvasPlan judges a plan without applying it. A plan with a refused
// op still answers 200, with Ok false.
func (g *Canvases) DryRunCanvasPlan(ctx context.Context, body CanvasPlan) (*Result[CanvasDryRun], error) {
	return bodyCall[CanvasDryRun](ctx, g.c, "dryRunCanvasPlan", "", body)
}

// PageCanvases yields every canvas the filters match, following NextCursor
// until a page says has_more false, the filters resent on every page. It
// never sleeps or polls. The last pair is (zero, err) when a page fails; a
// page with has_more and no next_cursor is a *TransportError of kind
// malformed_response, its items unyielded.
func (g *Canvases) PageCanvases(ctx context.Context, filters CanvasFilters) iter.Seq2[CanvasSummary, error] {
	return func(yield func(CanvasSummary, error) bool) {
		params := ListCanvasesOptions{Filters: filters}
		for {
			res, err := g.ListCanvases(ctx, params)
			if err == nil && res.Value.HasMore && res.Value.NextCursor == nil {
				err = &TransportError{Kind: MalformedResponse}
			}
			if err != nil {
				yield(CanvasSummary{}, err)
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

// bodyCall sends one canvas or dialogue operation with a JSON body; id is
// the path id, or "" for a path with none.
func bodyCall[T any](ctx context.Context, c *Client, operationID, id string, body any) (*Result[T], error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("lingara: %s: encoding the request body: %w", operationID, err)
	}
	rt := routes[operationID]
	return sendJSON[T](ctx, c, request{
		method: rt.method, url: c.url(rt.path, id), body: payload, accept: "application/json", needsToken: rt.needsToken,
	})
}

// canvasQuery is ListCanvases' query string.
func canvasQuery(params ListCanvasesOptions) url.Values {
	f := params.Filters
	q := url.Values{}
	single := map[string]string{"cursor": params.Cursor, "name": f.Name, "status": string(f.Status)}
	if f.Limit != 0 {
		single["limit"] = strconv.Itoa(f.Limit)
	}
	for key, value := range single {
		if value != "" {
			q.Set(key, value)
		}
	}
	for key, list := range map[string][]string{"recipes": f.Recipes, "kinds": f.Kinds, "scopes": f.Scopes} {
		if len(list) > 0 {
			q.Set(key, strings.Join(list, ","))
		}
	}
	return q
}
