# frozen_string_literal: true

module LingaraSnippets
  def self.stream_lesson_plan(client, plan_id)
    # lingara:begin streamLessonPlan
    client.stream_lesson_plan(plan_id) do |event|
      case event
      in Lingara::StreamLessonPlanEventResult => result then puts "ready: #{result.data.plan.title}"
      in Lingara::StreamLessonPlanEventPending => pending then puts "still #{pending.data.status}, try again later"
      else nil
      end
    end
    # lingara:end
  end
end
