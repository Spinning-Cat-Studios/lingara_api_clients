package snippets

import (
	"context"
	"fmt"
	"strings"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func sendDialogueTurn(ctx context.Context, client *lingara.Client, history []lingara.DialogueEntry) ([]lingara.DialogueEntry, error) {
	// lingara:begin sendDialogueTurn
	// One turn: at most 12 history entries, each and the line at most 500
	// characters. A turn is never retried, since each attempt spends NPC cells.
	persona := "a street-food vendor who likes to haggle"
	line := "饺子多少钱？"
	s, err := client.SendDialogueTurn(ctx, lingara.DialogueTurnRequest{
		Npc:        lingara.Npc{Name: "Auntie Lin", Persona: &persona},
		SourceLang: "en",
		TargetLang: "zh",
		Level:      3,
		Line:       line,
		History:    &history,
	})
	if err != nil {
		return history, err
	}
	defer s.Close()
	var reply strings.Builder
	for ev, err := range s.Events() {
		if err != nil {
			return history, err
		}
		if delta, ok := ev.(lingara.SendDialogueTurnEventDelta); ok {
			fmt.Print(delta.Data.Text)
			reply.WriteString(delta.Data.Text)
		}
	}
	// Send the reply back next turn, cut to its first 500 characters.
	npc := []rune(reply.String())
	history = append(history,
		lingara.DialogueEntry{Speaker: lingara.SpeakerPlayer, Text: line},
		lingara.DialogueEntry{Speaker: lingara.SpeakerNpc, Text: string(npc[:min(len(npc), 500)])})
	if len(history) > 12 {
		history = history[len(history)-12:]
	}
	// lingara:end
	return history, nil
}
