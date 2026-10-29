package lingara

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"reflect"
	"sync"
	"testing"
)

const testDialogueDefinition = `{"schema":"dialogue.v1","id":"night_market_keepers","start":"greet","npcs":[],"checkpoints":[],"facts":[],"x-layout":{"greet":{"x":1}}}`

const testDialogue = `{"id":"d-1","name":"Night market","created_at":"2026-10-28T09:00:00Z","updated_at":"2026-10-28T09:00:00Z",` +
	`"draft":{"revision":1,"draft_version":4,"definition":` + testDialogueDefinition + `},"active":null,"warnings":[]}`

// testIRJSON is deliberately not in Go's json.Marshal key order or spacing,
// so a decode and re-encode would show.
const testIRJSON = `{"start":"greet", "schema":"dialogue.v1","x-layout":{"greet":{"y":2,"x":1}}}`

const testETag = `"3b7e"`

func testDialoguePage(id, next string, more bool) string {
	cursor := "null"
	if next != "" {
		cursor = `"` + next + `"`
	}
	item := `{"id":"` + id + `","name":"Night market","ir_id":"night_market_keepers","created_at":"2026-10-28T09:00:00Z",` +
		`"updated_at":"2026-10-28T09:00:00Z","has_draft":true,"active_revision":null,"npc_count":1,"checkpoint_count":2}`
	return fmt.Sprintf(`{"items":[%s],"next_cursor":%s,"has_more":%t}`, item, cursor, more)
}

// dialogueAnswer is one scripted v1 answer: a status, headers and a body.
type dialogueAnswer struct {
	status int
	header map[string]string
	body   string
}

type dialogueSent struct {
	canvasSent
	ifNoneMatch string
}

// dialogueServer answers each v1 request with the next answer and records
// it.
func dialogueServer(t *testing.T, answers []dialogueAnswer) (*Dialogues, func() []dialogueSent) {
	t.Helper()
	var mu sync.Mutex
	var sent []dialogueSent
	srv := newServer(t, &tokenEndpoint{}, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		mu.Lock()
		n := len(sent)
		sent = append(sent, dialogueSent{canvasSent{r.Method, r.URL.RequestURI(), r.Header.Get("Content-Type"), body}, r.Header.Get("If-None-Match")})
		mu.Unlock()
		a := answers[n]
		for name, value := range a.header {
			w.Header().Set(name, value)
		}
		w.WriteHeader(a.status)
		fmt.Fprint(w, a.body)
	})
	recorded := func() []dialogueSent {
		mu.Lock()
		defer mu.Unlock()
		return append([]dialogueSent(nil), sent...)
	}
	return newTestClient(t, srv).Dialogues(), recorded
}

func dialogueJSON(body string) dialogueAnswer {
	return dialogueAnswer{status: 200, header: map[string]string{"Content-Type": "application/json"}, body: body}
}

// checkExports: a 200 export is the body unparsed with both headers, and a
// 304 is NotModified with both, never an error.
func checkExports(t *testing.T, dialogues *Dialogues) {
	t.Helper()
	ctx := context.Background()
	got, err := dialogues.ExportDialogue(ctx, "d/1", ExportDialogueOptions{Revision: ExportDialogueParamsRevisionDraft, Layout: ExportDialogueParamsLayoutOmit})
	want := ExportedDialogueBody{Revision: 3, ETag: testETag, IRJSON: testIRJSON}
	if err != nil || got.Value != ExportedDialogue(want) {
		t.Fatalf("export: %#v, %v", got, err)
	}
	got, err = dialogues.ExportDialogue(ctx, "d-1", ExportDialogueOptions{IfNoneMatch: testETag})
	if err != nil || got.Value != ExportedDialogue(ExportedDialogueNotModified{Revision: 3, ETag: testETag}) {
		t.Fatalf("export 304: %#v, %v", got, err)
	}
}

// pagedDialogueIDs walks PageDialogues and returns each item's id, or the
// error.
func pagedDialogueIDs(dialogues *Dialogues, filters DialogueFilters) ([]string, error) {
	var ids []string
	for item, err := range dialogues.PageDialogues(context.Background(), filters) {
		if err != nil {
			return ids, err
		}
		ids = append(ids, item.ID)
	}
	return ids, nil
}

// 29.10.26c AC18: through client.Dialogues(), PUT and PATCH reach the server
// with their JSON bodies and Content-Type application/json; the export's
// Body arm carries the unparsed bytes and both headers, a 304 is
// NotModified, and PageDialogues ends on has_more false with its filters
// resent.
func TestDialoguePutPatchExportAndPaging(t *testing.T) {
	exportHeaders := map[string]string{"Content-Type": "application/json", "ETag": testETag, "Lingara-Dialogue-Revision": "3"}
	dialogues, sent := dialogueServer(t, []dialogueAnswer{
		dialogueJSON(testDialogue), dialogueJSON(testDialogue),
		{status: 200, header: exportHeaders, body: testIRJSON},
		{status: 304, header: map[string]string{"ETag": testETag, "Lingara-Dialogue-Revision": "3"}},
		dialogueJSON(testDialoguePage("d-1", "dl1", true)), dialogueJSON(testDialoguePage("d-2", "", false)),
	})
	ctx := context.Background()
	var definition DialogueDefinition
	if err := json.Unmarshal([]byte(testDialogueDefinition), &definition); err != nil {
		t.Fatal(err)
	}
	_, err := dialogues.SaveDialogueDraft(ctx, "d-1", SaveDialogueDraftRequest{Definition: definition, DraftVersion: 4})
	if err == nil {
		_, err = dialogues.RenameDialogue(ctx, "d-1", RenameDialogueRequest{Name: "Renamed"})
	}
	if err != nil {
		t.Fatal(err)
	}
	checkExports(t, dialogues)
	ids, err := pagedDialogueIDs(dialogues, DialogueFilters{Limit: 1, Name: "market"})
	if err != nil || !reflect.DeepEqual(ids, []string{"d-1", "d-2"}) {
		t.Fatalf("paged %v, %v", ids, err)
	}
	checkDialogueSent(t, sent())
}

// checkDialogueSent asserts the six requests
// TestDialoguePutPatchExportAndPaging makes, in order.
func checkDialogueSent(t *testing.T, got []dialogueSent) {
	t.Helper()
	checkSent(t, got[0].canvasSent, "PUT", "/v1/dialogues/d-1/draft", `{"definition":`+testDialogueDefinition+`,"draft_version":4}`)
	checkSent(t, got[1].canvasSent, "PATCH", "/v1/dialogues/d-1", `{"name":"Renamed"}`)
	if got[2].uri != "/v1/dialogues/d%2F1/export?revision=draft&layout=omit" || got[2].ifNoneMatch != "" {
		t.Fatalf("export sent %s with If-None-Match %q", got[2].uri, got[2].ifNoneMatch)
	}
	if got[3].uri != "/v1/dialogues/d-1/export" || got[3].ifNoneMatch != testETag {
		t.Fatalf("export 304 sent %s with If-None-Match %q", got[3].uri, got[3].ifNoneMatch)
	}
	pages := []string{got[4].uri, got[5].uri}
	want := []string{"/v1/dialogues?limit=1&name=market", "/v1/dialogues?cursor=dl1&limit=1&name=market"}
	if !reflect.DeepEqual(pages, want) || len(got) != 6 {
		t.Fatalf("pages %v of %d requests", pages, len(got))
	}
}

// TestDialogueUnknownWarningCodeDecodes: 29.10.26c D4's Step 0 fact for Go.
// DialogueWarningCode is a string type, so a code a later spec adds still
// decodes a Dialogue.
func TestDialogueUnknownWarningCodeDecodes(t *testing.T) {
	raw := `{"id":"d-1","name":"N","created_at":"2026-10-28T09:00:00Z","updated_at":"2026-10-28T09:00:00Z",` +
		`"draft":null,"active":null,"warnings":[{"code":"some_future_warning","path":"/checkpoints/0"}]}`
	var d Dialogue
	if err := json.Unmarshal([]byte(raw), &d); err != nil || d.Warnings[0].Code != "some_future_warning" {
		t.Fatalf("%v, %+v", err, d.Warnings)
	}
}
