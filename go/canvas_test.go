package lingara

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"reflect"
	"sync"
	"testing"
)

const testDefinition = `{"schema":2,"nodes":[{"id":"n1","kind":"client","position":[0.5,1.5],"fields":{"name":"Lesson server","x_editor_hint":{"collapsed":true}}}]}`

const testCanvas = `{"id":"c-1","name":"Hooks","created_at":"2026-10-20T09:00:00Z","updated_at":"2026-10-20T09:00:00Z",` +
	`"draft":{"revision":1,"draft_version":4,"definition":` + testDefinition + `,"objects":{}},"active":null}`

func testCanvasPage(id, next string, more bool) string {
	cursor := "null"
	if next != "" {
		cursor = `"` + next + `"`
	}
	item := `{"id":"` + id + `","name":"Hooks","recipe":"blank","created_at":"2026-10-20T09:00:00Z","updated_at":"2026-10-20T09:00:00Z",` +
		`"has_draft":true,"active_revision":null,"node_count":1,"node_kinds":["client"],"scopes":[],"objects":[]}`
	hasMore := "false"
	if more {
		hasMore = "true"
	}
	return `{"items":[` + item + `],"total":null,"next_cursor":` + cursor + `,"has_more":` + hasMore + `}`
}

type canvasSent struct {
	method, uri, contentType string
	body                     []byte
}

// canvasServer answers each v1 request with the next answer and records it.
func canvasServer(t *testing.T, answers []string) (*Canvases, func() []canvasSent) {
	t.Helper()
	var mu sync.Mutex
	var sent []canvasSent
	srv := newServer(t, &tokenEndpoint{}, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		mu.Lock()
		n := len(sent)
		sent = append(sent, canvasSent{r.Method, r.URL.RequestURI(), r.Header.Get("Content-Type"), body})
		mu.Unlock()
		jsonAnswer(w, 200, answers[n])
	})
	recorded := func() []canvasSent {
		mu.Lock()
		defer mu.Unlock()
		return append([]canvasSent(nil), sent...)
	}
	return newTestClient(t, srv).Canvases(), recorded
}

// checkSent asserts one PUT or PATCH: method, path, JSON body and
// Content-Type.
func checkSent(t *testing.T, s canvasSent, method, uri, body string) {
	t.Helper()
	if s.method != method || s.uri != uri || s.contentType != "application/json" {
		t.Fatalf("sent %s %s (%q), want %s %s", s.method, s.uri, s.contentType, method, uri)
	}
	var got, want any
	if json.Unmarshal(s.body, &got) != nil || json.Unmarshal([]byte(body), &want) != nil || !reflect.DeepEqual(got, want) {
		t.Fatalf("sent %s, want %s", s.body, body)
	}
}

// pagedIDs walks PageCanvases and returns each item's id, or the error.
func pagedIDs(canvases *Canvases, filters CanvasFilters) ([]string, error) {
	var ids []string
	for item, err := range canvases.PageCanvases(context.Background(), filters) {
		if err != nil {
			return ids, err
		}
		ids = append(ids, item.ID)
	}
	return ids, nil
}

// checkMalformedPage: a page with has_more and no cursor ends the walk with
// malformed_response, its items unyielded.
func checkMalformedPage(t *testing.T, canvases *Canvases) {
	t.Helper()
	var tErr *TransportError
	if ids, err := pagedIDs(canvases, CanvasFilters{}); len(ids) != 0 || !errors.As(err, &tErr) || tErr.Kind != MalformedResponse {
		t.Fatalf("a page with has_more and no cursor: %v, %v", ids, err)
	}
}

// TestCanvasPutPatchAndPaging is 16.10.26ak AC11: through client.Canvases(),
// PUT and PATCH reach the server with their JSON bodies and Content-Type
// application/json; a definition read then saved keeps an unknown fields
// key; and PageCanvases ends on has_more false with its filters resent, and
// raises malformed_response on a page with has_more but no cursor.
func TestCanvasPutPatchAndPaging(t *testing.T) {
	canvases, sent := canvasServer(t, []string{testCanvas, testCanvas, testCanvas,
		testCanvasPage("c-1", "cv1", true), testCanvasPage("c-2", "", false), testCanvasPage("c-3", "", true)})
	ctx := context.Background()
	read, err := canvases.GetCanvas(ctx, "c-1")
	if err != nil {
		t.Fatal(err)
	}
	draft := read.Value.Draft
	_, err = canvases.SaveCanvasDraft(ctx, "c-1", SaveCanvasDraftRequest{Definition: draft.Definition, DraftVersion: draft.DraftVersion})
	if err == nil {
		_, err = canvases.RenameCanvas(ctx, "c-1", RenameCanvasRequest{Name: "Renamed"})
	}
	if err != nil {
		t.Fatal(err)
	}
	ids, err := pagedIDs(canvases, CanvasFilters{Limit: 1, Kinds: []string{"client", "webhook"}})
	if err != nil || !reflect.DeepEqual(ids, []string{"c-1", "c-2"}) {
		t.Fatalf("paged %v, %v", ids, err)
	}
	checkMalformedPage(t, canvases)

	got := sent()
	checkSent(t, got[1], "PUT", "/v1/canvases/c-1/draft", `{"definition":`+testDefinition+`,"draft_version":4}`)
	checkSent(t, got[2], "PATCH", "/v1/canvases/c-1", `{"name":"Renamed"}`)
	pages := []string{got[3].uri, got[4].uri}
	want := []string{"/v1/canvases?kinds=client%2Cwebhook&limit=1", "/v1/canvases?cursor=cv1&kinds=client%2Cwebhook&limit=1"}
	if !reflect.DeepEqual(pages, want) || len(got) != 6 {
		t.Fatalf("pages %v of %d requests", pages, len(got))
	}
}
