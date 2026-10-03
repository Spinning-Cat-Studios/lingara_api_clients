# frozen_string_literal: true

module LingaraSnippets
  def self.verify_webhook(secret, env)
    # lingara:begin verifyWebhook
    # A Rack endpoint: verify the raw body, before anything parses it. The
    # Rack env is the headers as it is.
    webhook = Lingara::Events::Webhook.new(secret)
    begin
      event = webhook.verify(env["rack.input"].read, env)
    rescue Lingara::Events::VerificationError => e
      return [400, {}, [e.reason.to_s]]
    end
    # Answer 2xx fast, and deduplicate by event.id: delivery is at least once.
    case event
    in Lingara::Events::LessonPlanReady(data:) then puts "plan #{data.plan_id} is ready"
    in Lingara::Events::UnknownEvent(type:) then puts "acknowledged #{type}, which this library does not know"
    else puts "#{event.type} #{event.id}"
    end
    [204, {}, []]
    # lingara:end
  end
end
