package snippets

import (
	"context"
	"fmt"

	lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
)

func generateVocabulary(ctx context.Context, client *lingara.Client) error {
	// lingara:begin generateVocabulary
	count := uint8(8)
	s, err := client.GenerateVocabulary(ctx, lingara.VocabRequest{Level: 2, SourceLang: "en", TargetLang: "zh", Count: &count})
	if err != nil {
		return err
	}
	defer s.Close()
	for ev, err := range s.Events() {
		if err != nil {
			return err
		}
		if item, ok := ev.(lingara.GenerateVocabularyEventItem); ok {
			fmt.Println(item.Data.Word, item.Data.Translation)
		}
	}
	// lingara:end
	return nil
}
