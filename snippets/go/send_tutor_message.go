package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func sendTutorMessage(ctx context.Context, client *lingara.Client) error {
	// lingara:begin sendTutorMessage
	s, err := client.SendTutorMessage(ctx, lingara.TutorTurnRequest{
		Message:    "你好！我想点一杯茶。",
		SourceLang: "en",
		TargetLang: "zh",
	})
	if err != nil {
		return err
	}
	defer s.Close()
	for ev, err := range s.Events() {
		if err != nil {
			return err
		}
		if delta, ok := ev.(lingara.SendTutorMessageEventDelta); ok {
			fmt.Print(delta.Data.Text)
		}
	}
	// lingara:end
	return nil
}
