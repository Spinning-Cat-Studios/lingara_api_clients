// Package sse is the server-sent-events parser: bytes in, frames out, no I/O
// (CONTRACT.md K5, Parsing; ADR 29.9.26q D4a).
//
// It works on bytes and splits lines at \r\n, \n or \r. Every line ending is
// ASCII, so a UTF-8 character split across chunks is carried whole in the
// line buffer and never decoded half. A \r that ends one chunk is remembered,
// so a \n opening the next is the same line ending rather than a blank line
// that would dispatch early. The frames are therefore the same however the
// bytes were chunked, down to one byte at a time.
package sse

import "strings"

// Frame is one dispatched frame: its event name and its joined data.
type Frame struct {
	Event string
	Data  string
}

// Parser holds the partial line and the frame being built. The zero value is
// ready to use.
type Parser struct {
	line    []byte
	event   string
	data    []string
	hasData bool
	// The last byte was \r: a \n next is the same line ending.
	skipLF bool
}

// Feed consumes bytes and returns every frame they completed.
func (p *Parser) Feed(b []byte) []Frame {
	var frames []Frame
	for _, c := range b {
		if p.skipLF {
			p.skipLF = false
			if c == '\n' {
				continue
			}
		}
		switch c {
		case '\n':
			frames = p.endLine(frames)
		case '\r':
			frames = p.endLine(frames)
			p.skipLF = true
		default:
			p.line = append(p.line, c)
		}
	}
	return frames
}

// End marks the end of input. An undispatched frame is discarded, as WHATWG
// says, so End never returns a frame; it exists so the end is explicit.
func (p *Parser) End() []Frame {
	*p = Parser{}
	return nil
}

func (p *Parser) endLine(frames []Frame) []Frame {
	line := string(p.line)
	p.line = p.line[:0]
	if line == "" {
		return p.dispatch(frames)
	}
	if strings.HasPrefix(line, ":") {
		return frames
	}
	field, value, found := strings.Cut(line, ":")
	if found {
		value = strings.TrimPrefix(value, " ")
	}
	switch field {
	case "event":
		p.event = value
	case "data":
		p.data = append(p.data, value)
		p.hasData = true
	}
	// id, retry and unknown fields are ignored.
	return frames
}

// dispatch ends the frame on a blank line. A frame with no data is dropped,
// and a frame with no event is named "message".
func (p *Parser) dispatch(frames []Frame) []Frame {
	event, data, hasData := p.event, p.data, p.hasData
	p.event, p.data, p.hasData = "", nil, false
	if !hasData {
		return frames
	}
	if event == "" {
		event = "message"
	}
	return append(frames, Frame{Event: event, Data: strings.Join(data, "\n")})
}
