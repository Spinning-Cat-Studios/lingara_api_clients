# frozen_string_literal: true

require "json"

module Lingara
  # What create_embed_token returns (ADR 1.10.26w D3): a player's embed
  # token and the six fields minted beside it. #token is an AccessToken, so
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
    # The schema's maxLength for a notice's message, in code points (never
    # bytes), so every library agrees on a notice near the limit.
    MESSAGE_MAX = 280

    # +token+ an AccessToken; +expires_at+ a Time, as the generated
    # EmbedToken reads it; +expires_in+ whole seconds, for a device whose
    # clock cannot be trusted; +subject+ the player's lgr_sub_, stable across
    # mints; +scopes+ as granted; +account_linked+ always false until a
    # player links an account; +update_available+ Lingara's notice that the
    # embed_version the request named is behind, deprecated or withdrawn
    # (an UpdateAvailable, copied verbatim), or nil when the request named
    # none, the version is current, or the notice was malformed. A notice is
    # advisory, so a bad one is dropped and never costs the token (ADR
    # 31.10.26g D3).
    attr_reader :token, :expires_at, :expires_in, :subject, :scopes, :account_linked, :update_available

    # A mint's answer body to a MintedToken. Every field must be present and
    # of its JSON type, and the token must start lgr_et_; anything else is
    # TransportError(:malformed_response), raised with no cause so nothing
    # of the body, the token included, travels with it. The notice is
    # deleted before those checks and the model, so it can fail neither.
    def self.decode(body)
      fields = JSON.parse(body)
      notice = fields.delete("update_available") if fields.is_a?(Hash)
      raise ArgumentError unless well_typed?(fields)
      model = EmbedToken.build_from_hash(fields)
      new(token: AccessToken.new(model.token), expires_at: model.expires_at, expires_in: model.expires_in,
        subject: model.subject, scopes: model.scopes, account_linked: model.account_linked,
        update_available: update_from(notice))
    rescue
      raise TransportError.new(:malformed_response, "the answer is not a minted token"), cause: nil
    end

    # The notice, when it is a Hash whose "deprecated" is a boolean and whose
    # "latest" and "message" are each present and a String or nil, "message"
    # at most 280 code points; otherwise nil. It reads the raw value by hand,
    # since the generated initialize raises on an unknown key, which is
    # ignored here.
    def self.update_from(notice)
      return nil unless notice.is_a?(Hash) && [true, false].include?(notice["deprecated"])
      return nil unless %w[latest message].all? { |key| notice.key?(key) && [String, NilClass].include?(notice[key].class) }
      return nil if notice["message"] && notice["message"].length > MESSAGE_MAX
      UpdateAvailable.new(latest: notice["latest"], deprecated: notice["deprecated"], message: notice["message"])
    end
    private_class_method :update_from

    def self.well_typed?(fields)
      return false unless fields.is_a?(Hash)
      typed = FIELDS.all? do |name, type|
        type.is_a?(Array) ? type.include?(fields[name]) : fields[name].is_a?(type)
      end
      typed && fields["token"].start_with?(PREFIX) && fields["scopes"].all?(String)
    end
    private_class_method :well_typed?

    def initialize(token:, expires_at:, expires_in:, subject:, scopes:, account_linked:, update_available: nil)
      @token = token
      @expires_at = expires_at
      @expires_in = expires_in
      @subject = subject.dup.freeze
      @scopes = scopes.map { |scope| scope.dup.freeze }.freeze
      @account_linked = account_linked
      @update_available = update_available
      freeze
    end

    def to_s
      inspect
    end

    def inspect
      "#<Lingara::MintedToken token=#{REDACTED} expires_at=#{@expires_at.inspect} expires_in=#{@expires_in} " \
        "subject=#{@subject.inspect} scopes=#{@scopes.inspect} account_linked=#{@account_linked} " \
        "update_available=#{@update_available&.to_hash.inspect}>"
    end
  end
end
