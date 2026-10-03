package sse

import (
	"reflect"
	"testing"
)

// exampleA is the body of CONTRACT.md's Example A (conformance case
// k5.vocab-split-frames), plus a CRLF frame and a trailing keepalive.
const exampleA = ": keepalive\n\n" +
	"event: started\ndata: {\"meta\":{\"level\":2,\"source_lang\":\"en\",\"target_lang\":\"zh\",\"framework\":\"HSK\",\"count\":2,\"ai_generated\":true}}\n" +
	"\nevent: item\ndata: {\"word\":\"你好\",\"pronunciation\":\"nǐ hǎo\",\"translation\":\"hello\"}\n\n" +
	"event: item\r\ndata: {\"word\":\"谢谢\",\"pronunciation\":\"xiè xie\",\"translation\":\"thank you\"}\r\n\r\n" +
	": keepalive\n\nevent: done\ndata: {}\n\n"

var exampleFrames = []Frame{
	{"started", `{"meta":{"level":2,"source_lang":"en","target_lang":"zh","framework":"HSK","count":2,"ai_generated":true}}`, ""},
	{"item", `{"word":"你好","pronunciation":"nǐ hǎo","translation":"hello"}`, ""},
	{"item", `{"word":"谢谢","pronunciation":"xiè xie","translation":"thank you"}`, ""},
	{"done", `{}`, ""},
}

func parse(chunks ...[]byte) []Frame {
	var p Parser
	var frames []Frame
	for _, c := range chunks {
		frames = append(frames, p.Feed(c)...)
	}
	return append(frames, p.End()...)
}

func texts(chunks ...string) []Frame {
	bs := make([][]byte, len(chunks))
	for i, c := range chunks {
		bs[i] = []byte(c)
	}
	return parse(bs...)
}

// TestFramesDoNotDependOnChunkBoundaries: 29.9.26q AC4. Every chunking of
// Example A into one, two or three pieces, and one byte at a time, yields the
// same frames, including splits inside a UTF-8 character and a \r\n split
// across two reads.
func TestFramesDoNotDependOnChunkBoundaries(t *testing.T) {
	b := []byte(exampleA)
	if got := parse(b); !reflect.DeepEqual(got, exampleFrames) {
		t.Fatalf("whole: got %q", got)
	}
	// Every two-piece split, and every three-piece split whose cuts are at
	// most eight bytes apart: enough to cut each multi-byte character twice.
	for i := 0; i <= len(b); i++ {
		for j := i; j <= min(i+8, len(b)); j++ {
			if got := parse(b[:i], b[i:j], b[j:]); !reflect.DeepEqual(got, exampleFrames) {
				t.Fatalf("split at %d, %d: got %q", i, j, got)
			}
		}
		if got := parse(b[:i], b[i:]); !reflect.DeepEqual(got, exampleFrames) {
			t.Fatalf("split at %d: got %q", i, got)
		}
	}
	single := make([][]byte, len(b))
	for i := range b {
		single[i] = b[i : i+1]
	}
	if got := parse(single...); !reflect.DeepEqual(got, exampleFrames) {
		t.Fatalf("byte at a time: got %q", got)
	}
	// The one split the loop above reaches only by chance: \r | \n.
	if got := texts("event: a\r", "\ndata: 1\r", "\n\r", "\n"); !reflect.DeepEqual(got, []Frame{{"a", "1", ""}}) {
		t.Fatalf("\\r\\n split across reads: got %q", got)
	}
}

// TestLineEndingsCommentsAndMultiLineData: 29.9.26q AC5. CRLF, CR and LF
// line endings, comments and multi-line data parse per C2 D6.
func TestLineEndingsCommentsAndMultiLineData(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want []Frame
	}{
		{"lf", "event: a\ndata: 1\n\n", []Frame{{"a", "1", ""}}},
		{"crlf", "event: a\r\ndata: 1\r\n\r\n", []Frame{{"a", "1", ""}}},
		{"cr", "event: a\rdata: 1\r\r", []Frame{{"a", "1", ""}}},
		{"comment dropped", ": keepalive\n\n: hi\nevent: a\ndata: 1\n\n", []Frame{{"a", "1", ""}}},
		{"multi-line data joined by \\n", "event: a\ndata: one\ndata: two\n\n", []Frame{{"a", "one\ntwo", ""}}},
		{"one leading space stripped", "data:  two spaces\ndata:none\n\n", []Frame{{"message", " two spaces\nnone", ""}}},
		{"no event is message", "data: 1\n\n", []Frame{{"message", "1", ""}}},
		{"no data is dropped", "event: a\n\n", nil},
		{"retry and unknown fields ignored; id recorded", "id: 7\nretry: 10\nfoo: bar\nevent: a\ndata: 1\n\n", []Frame{{"a", "1", "7"}}},
		{"undispatched frame discarded at end", "event: a\ndata: 1\n", nil},
	}
	for _, c := range cases {
		if got := texts(c.in); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

// TestIDIsRecordedAndPersists: ADR 30.9.26aa D7 (CONTRACT.md K5, Parsing).
// An id field sets the last-event-id buffer, which every later frame carries
// until the next id field; an id containing U+0000 is ignored, and an empty
// id clears the buffer.
func TestIDIsRecordedAndPersists(t *testing.T) {
	got := texts("id: c1\nevent: event\ndata: 1\n\n",
		"event: error\ndata: 2\n\n",
		"id: bad\x00id\nevent: event\ndata: 3\n\n",
		"id\nevent: done\ndata: 4\n\n")
	want := []Frame{{"event", "1", "c1"}, {"error", "2", "c1"}, {"event", "3", "c1"}, {"done", "4", ""}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %q, want %q", got, want)
	}
}
