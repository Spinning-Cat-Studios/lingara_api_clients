# frozen_string_literal: true

module LingaraSnippets
  def self.send_event(client)
    # lingara:begin sendEvent
    event = Lingara::Events::InboundEvent.world_context_changed(
      scene: "A night market after rain", source_lang: "en", target_lang: "zh", level: 2, generate: true
    )
    # Your own key makes a resend after a crash safe; reusing it for another
    # event returns the first answer.
    accepted = client.send_event(event, idempotency_key: "save-17/night-market").value
    puts "sent #{accepted.id}"
    # Only a plan still generating promises lesson_plan.ready or .failed.
    puts "plan #{accepted.reaction.plan_id} is on its way" if accepted.reaction&.plan_status == "generating"
    # lingara:end
  end
end
