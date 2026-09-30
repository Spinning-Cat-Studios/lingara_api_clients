# frozen_string_literal: true

module LingaraSnippets
  def self.get_lesson_plan(client, plan_id)
    # lingara:begin getLessonPlan
    plan = client.get_lesson_plan(plan_id).value
    puts "#{plan.title} (#{plan.status})"
    plan.content&.vocabulary&.each { |word| puts "#{word.word}: #{word.translation}" }
    # lingara:end
  end
end
