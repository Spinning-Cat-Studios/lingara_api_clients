# frozen_string_literal: true

module Lingara
  # Lingara's events (ADR 30.9.26aa): the catalogue's union and its parser
  # (catalogue.rb, generated from the view's x-lingara-events), the webhook
  # verifier, the feed and the tail. Every door carries one envelope, and
  # Lingara::Events.parse reads it into an Event.
  module Events
  end
end

require_relative "events/catalogue"
require_relative "events/webhook"
require_relative "events/feed"
require_relative "events/tail"
