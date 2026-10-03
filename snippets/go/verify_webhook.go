package snippets

import (
	"fmt"
	"io"
	"net/http"
	"os"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func verifyWebhook() (http.Handler, error) {
	// lingara:begin verifyWebhook
	// The endpoint's secret, or both during a rotation.
	webhook, err := lingara.NewWebhook(os.Getenv("LINGARA_WEBHOOK_SECRET"))
	if err != nil {
		return nil, err
	}
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify the raw bytes, before anything parses them.
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "unreadable body", http.StatusBadRequest)
			return
		}
		ev, err := webhook.Verify(body, r.Header)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		// Delivery is at least once: deduplicate by ev.Meta().ID.
		switch e := ev.(type) {
		case lingara.LessonPlanReady:
			fmt.Println("plan ready:", e.Data.PlanID)
		case lingara.UnknownEvent:
			fmt.Println("acknowledged a newer event type:", e.Type)
		}
		w.WriteHeader(http.StatusNoContent)
	})
	// lingara:end
	return handler, nil
}
