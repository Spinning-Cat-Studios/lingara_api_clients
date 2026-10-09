# frozen_string_literal: true

require "json"

module Lingara
  # What create_embed_token returns (ADR 1.10.26w D3): a player's embed
  # token and the five fields minted beside it. #token is an AccessToken, so
  # the lgr_et_ value renders [REDACTED] in #inspect, #to_s and pp like
  # every other token (K1); token.expose_secret reads it.
  #
  # Lives at lingara/minted_token, never lingara/embed: that require path is
  # the lingara-embed gem's, and two gems shipping one path shadow each other
  # on $LOAD_PATH.
  class MintedToken
    include Redacted

    PREFIX = "lgr_et_"
    # Each answer field and the JSON type it must have.
    FIELDS = {
      "token" => String, "expires_at" => String, "expires_in" => Integer, "subject" => String, "scopes" => Array,
      "account_linked" => [true, false]
    }.freeze

    # +token+ an AccessToken; +expires_at+ a Time, as the generated
    # EmbedToken reads it; +expires_in+ whole seconds, for a device whose
    # clock cannot be trusted; +subject+ the player's lgr_sub_, stable across
    # mints; +scopes+ as granted; +account_linked+ always false until a
    # player links an account.
    attr_reader :token, :expires_at, :expires_in, :subject, :scopes, :account_linked

    # A mint's answer body to a MintedToken. Every field must be present and
    # of its JSON type, and the token must start lgr_et_; anything else is
    # TransportError(:malformed_response), raised with no cause so nothing
    # of the body, the token included, travels with it.
    def self.decode(body)
      fields = JSON.parse(body)
      raise ArgumentError unless well_typed?(fields)
      model = EmbedToken.build_from_hash(fields)
      new(token: AccessToken.new(model.token), expires_at: model.expires_at, expires_in: model.expires_in,
        subject: model.subject, scopes: model.scopes, account_linked: model.account_linked)
    rescue
      raise TransportError.new(:malformed_response, "the answer is not a minted token"), cause: nil
    end

    def self.well_typed?(fields)
      return false unless fields.is_a?(Hash)
      typed = FIELDS.all? do |name, type|
        type.is_a?(Array) ? type.include?(fields[name]) : fields[name].is_a?(type)
      end
      typed && fields["token"].start_with?(PREFIX) && fields["scopes"].all?(String)
    end
    private_class_method :well_typed?

    def initialize(token:, expires_at:, expires_in:, subject:, scopes:, account_linked:)
      @token = token
      @expires_at = expires_at
      @expires_in = expires_in
      @subject = subject.dup.freeze
      @scopes = scopes.map { |scope| scope.dup.freeze }.freeze
      @account_linked = account_linked
      freeze
    end

    def to_s
      inspect
    end

    def inspect
      "#<Lingara::MintedToken token=#{REDACTED} expires_at=#{@expires_at.inspect} expires_in=#{@expires_in} " \
        "subject=#{@subject.inspect} scopes=#{@scopes.inspect} account_linked=#{@account_linked}>"
    end
  end
end
