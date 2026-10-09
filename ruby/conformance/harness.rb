# frozen_string_literal: true

# The Ruby library's conformance harness (conformance/README.md, Writing a
# harness; ADR 29.9.26t D7). Standard library only, so it runs as
# `ruby -Iruby/lib ruby/conformance/harness.rb` with no bundle exec.
#
# Every client is built from the case's client block through Client.new's
# keywords only, with a virtual clock and a recording sleeper. A
# `parallel: n` step is n threads released together from one Queue, and
# `cancel_after_events: n` breaks out of the stream's block after its n-th
# event: Ruby's native cancellation of a block-driven stream.

require "json"
require "net/http"
require "pp"
require "socket"
require "stringio"
require "time"
require "uri"
require "lingara"

module Harness
  CLOCK_START = 1_790_000_000
  STREAMS = {
    "generateVocabulary" => :generate_vocabulary, "createLessonPlan" => :create_lesson_plan,
    "sendTutorMessage" => :send_tutor_message, "sendDialogueTurn" => :send_dialogue_turn
  }.freeze
  # The embed operations (ADR 1.10.26w): the mint takes the case's body,
  # the delete its params.player_ref and answers 204.
  EMBED_CALLS = %w[createEmbedToken deleteEmbedPlayer].freeze
  JSON_CALLS = {
    "getUsage" => :get_usage, "getOpenApiDocument" => :get_open_api_document, "listApiVersions" => :list_api_versions,
    "getAsyncApiDocument" => :get_async_api_document
  }.freeze
  WITH_ID = {"getLessonPlan" => :get_lesson_plan, "getApiVersion" => :get_api_version, "streamLessonPlan" => :stream_lesson_plan}.freeze
  # The event operations take the case's params as keywords (ADR 30.9.26aa).
  EVENT_CALLS = %w[listEvents streamEvents sendEvent].freeze
  # The event helpers a step drives: `events` to its end, `tail` for `take`.
  HELPERS = {"events" => :events, "tail" => :tail_events}.freeze

  # One case's client, its virtual clock, and what one step recorded.
  class Rig
    attr_reader :client

    def initialize(block, base, token_url)
      @mutex = Mutex.new
      @now = CLOCK_START
      @sleeps = []
      @hooks = []
      @client = Lingara::Client.new(base_url: base, token_url: token_url, **Harness.client_keywords(block, self))
    end

    def now
      @mutex.synchronize { Time.at(@now) }
    end

    def advance(seconds)
      @mutex.synchronize { @now += seconds }
    end

    def sleep_for(seconds)
      @mutex.synchronize { @sleeps << seconds }
    end

    def hook(notice)
      @mutex.synchronize { @hooks << Harness.hook_record(notice) }
    end

    def reset
      @mutex.synchronize { @sleeps, @hooks = [], [] }
    end

    def sleeps_s
      @mutex.synchronize { @sleeps.map(&:round) }
    end

    def hook_calls
      @mutex.synchronize { @hooks.dup }
    end
  end

  module_function

  def client_keywords(block, rig)
    keywords = {clock: -> { rig.now }, sleeper: ->(seconds) { rig.sleep_for(seconds) }}
    if (cred = block["credentials"])
      keywords.update(client_id: cred["client_id"], client_secret: cred["client_secret"], auth: (cred["auth"] || "basic").to_sym)
    end
    keywords[:version] = block["version"] if block.key?("version")
    keywords[:stream_idle_timeout] = block["stream_idle_timeout_ms"] / 1000.0 if block["stream_idle_timeout_ms"]
    keywords[:on_deprecation] = ->(notice) { rig.hook(notice) } if block["deprecation_hook"] == "record"
    keywords.merge(plain_keywords(block))
  end

  # The client-block keys that map onto a keyword unchanged.
  def plain_keywords(block)
    retries = block["retries"] || {}
    {scopes: block["scopes"], max_attempts: retries["max_attempts"], retry_after_cap: retries["retry_after_cap_s"],
     user_agent_suffix: block["user_agent_suffix"]}.compact
  end

  def hook_record(notice)
    link = notice.link && {"raw" => notice.link.raw, "target" => notice.link.target&.to_s}
    {"version" => notice.version, "deprecated_at" => notice.deprecated_at&.to_i, "sunset_at" => notice.sunset_at&.to_i, "link" => link}
  end

  # ── One call, as the harness saw it ──────────────────────────────────────

  def invoke(client, call)
    operation = call["operation"]
    id = (call["params"] || {})["id"]
    cancel_after = call["cancel_after_events"]
    if STREAMS.key?(operation) || operation == "streamLessonPlan"
      args = STREAMS.key?(operation) ? [STREAMS[operation]] : [:stream_lesson_plan, id]
      consume(client, args, body_of(call), cancel_after)
    elsif WITH_ID.key?(operation)
      result { client.public_send(WITH_ID[operation], id) }
    elsif JSON_CALLS.key?(operation)
      result { client.public_send(JSON_CALLS[operation]) }
    elsif EVENT_CALLS.include?(operation)
      invoke_event(client, call)
    elsif EMBED_CALLS.include?(operation)
      invoke_embed(client, call)
    else
      {outcome: "harness: no operation #{operation}"}
    end
  end

  # The call's body, with keyword keys.
  def body_of(call)
    (call["body"] || {}).transform_keys(&:to_sym)
  end

  def invoke_embed(client, call)
    if call["operation"] == "deleteEmbedPlayer"
      result(204) { client.delete_embed_player((call["params"] || {})["player_ref"]) }
    else
      result { client.create_embed_token(**body_of(call)) }
    end
  end

  # A MintedToken in wire form, read through the exposing accessor:
  # snake_case keys, expires_at as received, expires_in in seconds.
  def wire(value)
    return value unless value.is_a?(Lingara::MintedToken)
    {"token" => value.token.expose_secret, "expires_at" => value.expires_at, "expires_in" => value.expires_in,
     "subject" => value.subject, "scopes" => value.scopes, "account_linked" => value.account_linked}
  end

  # sendEvent's InboundEvent is built from the case's {type, data} through
  # the public constructors, and its 202 is the only 2xx it answers.
  def invoke_event(client, call)
    params = (call["params"] || {}).transform_keys(&:to_sym)
    case call["operation"]
    when "streamEvents" then consume(client, [:stream_events], params, call["cancel_after_events"])
    when "listEvents" then result { client.list_events(**params) }
    else
      body = call["body"] || {}
      constructor = Lingara::Events::InboundEvent::CONSTRUCTORS.fetch(body["type"])
      event = Lingara::Events::InboundEvent.public_send(constructor, body["data"])
      result(202) { client.send_event(event, **{idempotency_key: call["idempotency_key"]}.compact) }
    end
  end

  # A completed call's result joins the renderings `redacted` scans (D7).
  def result(status = 200)
    response = yield
    {outcome: "completed", status: status, body: wire(response.value), served_version: response.served_version,
     renderings: renderings(response) + renderings(response.value)}
  rescue Lingara::Error => e
    failed(e, nil)
  end

  # Drives one event helper: `events` to its end, or `tail` until `take`
  # events, then leaves its block, which is the tail's cancellation.
  def drive(client, kind, options)
    keywords = {cursor: options["cursor"], start: options["start"], types: options["types"]}.compact
    helper = client.public_send(HELPERS.fetch(kind), **keywords)
    seen = []
    outcome = begin
      helper.each do |event|
        seen << event
        break if seen.size == options["take"]
      end
      {outcome: "completed"}
    rescue Lingara::Error => e
      failed(e, nil)
    end
    outcome.merge(event_ids: seen.map(&:id), unknown_types: seen.grep(Lingara::Events::UnknownEvent).map(&:type),
      cursor: helper.cursor)
  end

  # Drains a stream through its block; after +cancel_after+ events it breaks
  # out, which closes the connection.
  def consume(client, args, body, cancel_after)
    seen = []
    cancelled = false
    stream_result = client.public_send(*args, **body) do |event|
      seen << event
      if seen.size == cancel_after
        cancelled = true
        break
      end
    end
    outcome = cancelled ? "cancelled" : "completed"
    {outcome: outcome, status: (200 unless cancelled), events: seen, served_version: stream_result&.served_version}
  rescue Lingara::Error => e
    failed(e, seen)
  end

  def failed(error, events)
    variant, fields = error_fields(error)
    served = error.respond_to?(:served_version) ? error.served_version : nil
    {outcome: "error", events: events, variant: variant, fields: fields, served_version: served, renderings: renderings(error)}
  end

  def error_fields(error)
    case error
    when Lingara::ApiError
      ["ApiError", {"status" => error.status, "code" => error.code, "message" => error.message, "retry_after" => error.retry_after,
                    "plan_id" => error.plan_id, "served_version" => error.served_version}]
    when Lingara::OAuthError
      ["OAuthError", {"status" => error.status, "error" => error.error, "description" => error.description, "retry_after" => error.retry_after}]
    when Lingara::MaintenanceError
      ["MaintenanceError", {"body" => error.body, "retry_after" => error.retry_after}]
    when Lingara::TransportError
      ["TransportError", {"kind" => error.kind.to_s}]
    else
      ["not a known variant", {"debug" => error.class.name}]
    end
  end

  # Every rendering of an object Ruby offers: #inspect, #to_s and pp, plus
  # #message and #full_message for an error, down its cause chain.
  def renderings(object)
    out = []
    while object
      out.push(object.inspect, object.to_s, PP.pp(object, +""))
      out.push(object.message, object.full_message(highlight: false)) if object.is_a?(Exception)
      object = object.respond_to?(:cause) ? object.cause : nil
    end
    out
  end

  # ── Comparison (conformance/README.md, Comparison rules) ─────────────────

  # Plain JSON data: models through #to_hash, Times as the server's
  # ISO 8601, and null-valued keys dropped.
  def plain(value)
    case value
    when Hash then value.each_with_object({}) { |(k, v), h| h[k.to_s] = plain(v) unless v.nil? }
    when Array then value.map { |v| plain(v) }
    when Time then (value.subsec.zero? ? value.utc.iso8601 : value.utc.iso8601(9).sub(/0+Z\z/, "Z"))
    when Symbol then value.to_s
    else value.respond_to?(:to_hash) ? plain(value.to_hash) : value
    end
  end

  def canon(value)
    JSON.generate(sort(plain(value)))
  end

  def sort(value)
    case value
    when Hash then value.sort.to_h { |k, v| [k, sort(v)] }
    when Array then value.map { |v| sort(v) }
    else value
    end
  end

  def substitute(value, base)
    case value
    when String then value.gsub("{base_url}", base)
    when Array then value.map { |v| substitute(v, base) }
    when Hash then value.transform_values { |v| substitute(v, base) }
    else value
    end
  end

  def compare(expect, seen)
    out = []
    if seen[:outcome] != expect["outcome"]
      detail = seen[:variant] ? " (#{seen[:variant]} #{canon(seen[:fields])})" : ""
      out << "outcome: expected #{expect["outcome"]}, got #{seen[:outcome]}#{detail}"
    end
    compare_values(expect, seen, out)
    compare_cursor(expect["cursor"], seen[:cursor], out) if expect.key?("cursor")
    compare_error(expect["error"], seen, out) if expect["error"]
    compare_redacted(expect["redacted"] || [], seen, out)
  end

  def compare_values(expect, seen, out)
    got = {"status" => seen[:status] || seen.dig(:fields, "status"), "body" => seen[:body], "events" => seen[:events] || [],
           "served_version" => seen[:served_version], "sleeps_s" => seen[:sleeps], "hook_calls" => seen[:hooks] || [],
           "event_ids" => seen[:event_ids] || [], "unknown_types" => seen[:unknown_types] || []}
    got.each do |label, value|
      next unless expect.key?(label)
      want = canon(expect[label])
      have = canon(value)
      out << "#{label}: expected #{want}, got #{have}" unless want == have
    end
  end

  # The helper's final cursor against a matcher: equals, prefix, contains,
  # pattern or absent.
  def compare_cursor(matcher, cursor, out)
    name, want = matcher.first
    ok = case name
    when "equals" then cursor == want
    when "prefix" then cursor.to_s.start_with?(want)
    when "contains" then cursor.to_s.include?(want)
    when "pattern" then cursor.to_s.match?(Regexp.new(want))
    when "absent" then cursor.nil?
    else false
    end
    out << "cursor: expected #{canon(matcher)}, got #{cursor.inspect}" unless ok
    out
  end

  def compare_error(want, seen, out)
    return out << "error: expected #{want["variant"]}, got none" unless seen[:variant]
    out << "error.variant: expected #{want["variant"]}, got #{seen[:variant]}" if seen[:variant] != want["variant"]
    (want["fields"] || {}).each do |name, value|
      w = canon(value)
      g = canon(seen[:fields][name])
      out << "error.#{name}: expected #{w}, got #{g}" unless w == g
    end
    out
  end

  def compare_redacted(secrets, seen, out)
    secrets.each do |secret|
      next if secret.empty?
      out << "redacted: a rendering contains #{secret[0, 12]}…" if seen[:renderings].any? { |r| r.include?(secret) }
    end
    out
  end

  # ── The case loop ────────────────────────────────────────────────────────

  def run_step(rig, call, expect)
    rig.reset
    n = call["parallel"] || 1
    gate = Queue.new
    threads = Array.new(n) { Thread.new { gate.pop and invoke(rig.client, call) } }
    n.times { gate << true }
    runs = threads.map(&:value)
    runs.each_with_index.flat_map do |seen, i|
      seen[:sleeps] = rig.sleeps_s
      seen[:hooks] = rig.hook_calls
      seen[:renderings] = (seen[:renderings] || []) + renderings(rig.client) + renderings(rig.client.token_source)
      label = (n > 1) ? "call #{i + 1}: " : ""
      compare(expect, seen).map { |m| "#{call["operation"]}: #{label}#{m}" }
    end
  end

  def run_helper(rig, kind, options, expect)
    rig.reset
    seen = drive(rig.client, kind, options)
    seen[:sleeps] = rig.sleeps_s
    seen[:hooks] = rig.hook_calls
    seen[:renderings] = (seen[:renderings] || []) + renderings(rig.client) + renderings(rig.client.token_source)
    compare(expect, seen).map { |m| "#{kind}: #{m}" }
  end

  # A call step, an event-helper step, or neither (a bare advance_clock_s).
  def run_any(rig, step, base)
    helper = HELPERS.keys.find { |kind| step.key?(kind) }
    if step["call"] && step["expect"] then run_step(rig, step["call"], substitute(step["expect"], base))
    elsif helper then run_helper(rig, helper, step[helper] || {}, substitute(step["expect"] || {}, base))
    else []
    end
  end

  def steps(env, kase)
    block = kase["client"] || {}
    base, token_url = urls(env, block)
    rig = Rig.new(block, base, token_url)
    (kase["steps"] || []).flat_map do |step|
      rig.advance(step["advance_clock_s"]) if step["advance_clock_s"]
      run_any(rig, step, env[:base])
    end
  rescue => e
    ["harness: #{e.class}: #{e.message}"]
  end

  # The case server, or for `base_url: unreachable` a port bound and released
  # so nothing listens.
  def urls(env, block)
    return [env[:base], env[:token]] unless block["base_url"] == "unreachable"
    server = TCPServer.new("127.0.0.1", 0)
    base = "http://127.0.0.1:#{server.addr[1]}"
    server.close
    [base, "#{base}/oauth/token"]
  end

  def control(method, url)
    uri = URI(url)
    response = Net::HTTP.start(uri.host, uri.port) do |http|
      http.request(Net::HTTPGenericRequest.new(method, false, true, uri))
    end
    raise "#{url}: #{response.code} #{response.body}" unless response.is_a?(Net::HTTPSuccess)
    JSON.parse(response.body)
  end

  def run_case(env, id)
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    kase = control("GET", "#{env[:control]}/cases/#{id}")
    control("POST", "#{env[:control]}/cases/#{id}/arm")
    client = steps(env, kase)
    server = control("POST", "#{env[:control]}/cases/#{id}/finish")["mismatches"] || []
    pass = client.empty? && server.empty?
    line = {"case" => id, "lang" => "ruby", "library_version" => Lingara::VERSION, "result" => pass ? "pass" : "fail",
            "client_mismatches" => client, "server_mismatches" => server,
            "duration_ms" => ((Process.clock_gettime(Process::CLOCK_MONOTONIC) - started) * 1000).round}
    File.open(env[:out], "a") { |f| f.puts(JSON.generate(line)) }
    warn("✗ #{id}: client #{client} server #{server}") unless pass
    pass
  end

  def env
    names = {base: "LINGARA_CONFORMANCE_BASE_URL", token: "LINGARA_CONFORMANCE_TOKEN_URL",
             control: "LINGARA_CONFORMANCE_CONTROL_URL", out: "LINGARA_CONFORMANCE_OUT"}
    values = names.transform_values do |name|
      ENV.fetch(name) { abort("✗ harness: #{name} is not set: run this through conformance-server run") }
    end
    only = ENV["LINGARA_CONFORMANCE_ONLY"].to_s.split(",").map(&:strip).reject(&:empty?)
    values.merge(only: only)
  end

  def main
    e = env
    ids = control("GET", "#{e[:control]}/cases")
    ids &= e[:only] unless e[:only].empty?
    results = ids.map { |id| run_case(e, id) }
    exit(results.all? ? 0 : 1)
  end
end

Harness.main if $PROGRAM_NAME == __FILE__
