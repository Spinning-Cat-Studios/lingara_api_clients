# frozen_string_literal: true

require "support/fake_server"

class UserAgentTest < Minitest::Test
  PATTERN = /\Alingara-(typescript|rust|go|java|kotlin|ruby|php)\/(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)? \([\x20-\x28\x2A-\x7E]+\)( .+)?\z/

  # 29.9.26t AC25: C2 D8's pattern with <lang> ruby on the token request and
  # a /v1 request, for a pre-release VERSION too, with a suffix after it.
  def test_user_agent_shape_and_suffix
    server = Fixtures.server { |_, conn| conn.json(200, {allowance: []}) }
    server.client(**Fixtures.credentials, user_agent_suffix: "kanji-quest/2.1").get_usage
    agents = server.requests.map { |r| r.headers["user-agent"] }
    assert_equal 2, agents.size
    agents.each do |agent|
      assert_match PATTERN, agent
      assert agent.start_with?("lingara-ruby/#{Lingara::VERSION} (ruby/#{RUBY_VERSION}; ")
      assert agent.end_with?(") kanji-quest/2.1")
    end
    assert_match PATTERN, Lingara::UserAgent.build(version: "0.1.0-alpha.1")
    assert_equal "lingara-ruby/0.1.0 (ruby/unknown)", Lingara::UserAgent.build(version: "0.1.0", runtime: "ruby/3.3 (x)")
    assert_equal "lingara-ruby/0.1.0 (ruby/unknown)", Lingara::UserAgent.build(version: "0.1.0", runtime: "ruby/3.3\n")
  ensure
    server&.close
  end
end
