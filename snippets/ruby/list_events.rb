# frozen_string_literal: true

module LingaraSnippets
  def self.list_events(client, saved_cursor)
    # lingara:begin listEvents
    # Every event after saved_cursor (or, with none, from now on), page by
    # page, to the horizon. It never waits: call it again later.
    feed = client.events(cursor: saved_cursor)
    feed.each do |event|
      case event
      in Lingara::Events::LessonPlanReady(data:) then puts "plan #{data.plan_id} is ready"
      in Lingara::Events::LessonPlanFailed(data:) then puts "plan #{data.plan_id} failed: #{data.reason}"
      else puts "#{event.type} #{event.id}"
      end
    end
    puts "next time, resume from #{feed.cursor}"
    # lingara:end
  end
end
