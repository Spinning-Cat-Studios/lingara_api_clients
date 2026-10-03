# frozen_string_literal: true

module LingaraSnippets
  def self.stream_events(client, saved_cursor)
    # lingara:begin streamEvents
    # Live events after saved_cursor. The tail reconnects on its own, from
    # tail.cursor; leave the block to stop it.
    tail = client.tail_events(cursor: saved_cursor)
    tail.each do |event|
      next unless event in Lingara::Events::LessonPlanReady
      puts "plan #{event.data.plan_id} is ready (resume from #{tail.cursor})"
      break
    end
    # lingara:end
  end
end
