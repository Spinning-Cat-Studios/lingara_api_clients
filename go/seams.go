package lingara

import (
	"context"
	"time"
)

// The two testing seams (CONTRACT.md, Test seams; ADR 29.9.26q D3). Refresh
// timing and Retry-After sleeps cannot be tested in real time, so WithClock
// and WithSleeper replace these. Nothing else should.

// realSleep waits for d, or returns ctx.Err() as soon as ctx is done.
func realSleep(ctx context.Context, d time.Duration) error {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
