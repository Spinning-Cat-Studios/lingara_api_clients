// Package lingara is the official Go library for the Lingara API. It uses the
// standard library alone, so the module has no requirements.
//
// Import it under the name lingara; the import path ends in /go:
//
//	import lingara "github.com/Spinning-Cat-Studios/lingara_api_clients/go"
//
// Build a client, then call an operation. A stream operation sends its
// request at once and returns when the headers are in; range over its events
// once, and defer Close:
//
//	client, err := lingara.New(lingara.WithClientCredentials(clientID, clientSecret))
//	if err != nil {
//		return err
//	}
//	s, err := client.GenerateVocabulary(ctx, lingara.VocabRequest{Level: 2, SourceLang: "en", TargetLang: "zh"})
//	if err != nil {
//		return err
//	}
//	defer s.Close()
//	for ev, err := range s.Events() {
//		if err != nil {
//			return err // a lingara error, or ctx.Err()
//		}
//		if item, ok := ev.(lingara.GenerateVocabularyEventItem); ok {
//			fmt.Println(item.Data.Word, item.Data.Translation)
//		}
//	}
//
// Every error a call returns, cancellation aside, implements [Error]: use
// errors.As with *[APIError], *[OAuthError], *[MaintenanceError] or
// *[TransportError]. Cancelling ctx returns ctx.Err(), never one of those.
//
// [WithClock] and [WithSleeper] are testing seams. A caller-supplied
// http.Client whose Timeout is set caps every call, streams included; the
// stream idle timeout ([WithStreamIdleTimeout]) holds either way.
//
// The library keeps the contract every official Lingara library keeps:
// https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/conformance/CONTRACT.md
package lingara
