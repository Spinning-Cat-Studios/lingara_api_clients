# frozen_string_literal: true

module LingaraSnippets
  def self.create_lesson_plan(client)
    # lingara:begin createLessonPlan
    client.create_lesson_plan(context: "ordering at a night market", source_lang: "en", target_lang: "zh", level: 2) do |event|
      case event
      in Lingara::CreateLessonPlanEventStarted => started then puts "plan #{started.data.plan_id}"
      in Lingara::CreateLessonPlanEventPhase => phase then puts "working: #{phase.data.phase}"
      in Lingara::CreateLessonPlanEventResult => result then puts "ready: #{result.data.plan.title}"
      else nil
      end
    end
    # lingara:end
  end
end
