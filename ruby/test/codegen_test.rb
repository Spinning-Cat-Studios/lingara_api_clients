# frozen_string_literal: true

require "open3"
require "rbconfig"
require "tmpdir"
require "support/fake_server"

class CodegenTest < Minitest::Test
  SCRIPT = File.expand_path("../codegen/generate.rb", __dir__)
  VIEW = File.expand_path("fixtures/view.json", __dir__)

  def generate(out, version_file)
    Open3.capture3(RbConfig.ruby, SCRIPT, "--view", VIEW, "--version", version_file, "--out", out)
  end

  # Loads a generated file into a fresh module standing in for Lingara, so
  # the fixture's routes never meet the real ones.
  def load_constants(path)
    sandbox = Module.new
    sandbox.module_eval(File.read(path).sub("module Lingara", "module Generated"), path)
    sandbox.const_get(:Generated)
  end

  # 29.9.26t AC3: over a fixture view, one union module and one decoder per
  # stream, the routes with the streams' event names and terminal tables,
  # both version constants; two runs byte-identical; and a models directory
  # holding a union file refused.
  def test_fixture_view_yields_unions_operations_and_version
    Dir.mktmpdir do |dir|
      version_file = File.join(dir, "VERSION")
      File.write(version_file, "0.1.0-alpha.1\n")
      out = File.join(dir, "out")
      Dir.mkdir(out)
      _, err, status = generate(out, version_file)
      assert status.success?, err

      streams = File.read(File.join(out, "streams.rb"))
      assert_equal %w[FollowPlanEvent StreamWordsEvent], streams.scan(/^  module (\w+)$/).flatten
      assert_equal 2, streams.scan("def self.decode(name, data)").size

      operations = load_constants(File.join(out, "operations.rb"))::OPERATIONS
      assert_equal %w[followPlan getA getB getC putD streamWords], operations.keys
      assert_equal({"started" => "StreamWordsEventStarted", "word" => "StreamWordsEventWord", "done" => "StreamWordsEventDone",
                    "error" => "StreamWordsEventError"}, operations["streamWords"][:stream][:events])
      assert_equal({"done" => :quiet, "error" => :raise}, operations["streamWords"][:stream][:ends])
      assert_equal({"result" => :yield, "pending" => :yield, "error" => :raise}, operations["followPlan"][:stream][:ends])
      assert_equal ["id"], operations["followPlan"][:path_params]
      assert_equal "Thing", operations["getA"][:response]
      assert_nil operations["getC"][:response]
      assert_equal [true, false], [operations["getA"][:needs_token], operations["getC"][:needs_token]]
      assert_equal "Thing", operations["putD"][:request_body]

      version = load_constants(File.join(out, "version.rb"))
      assert_equal ["0.1.0-alpha.1", "0.1.0.pre.alpha.1", "2026-09-fixture-view"],
        [version::VERSION, version::GEM_VERSION, version::GENERATED_FOR_VERSION]

      catalogue = File.read(File.join(out, "events", "catalogue.rb"))
      assert_equal %w[WordReady PingTest UnknownEvent], catalogue.scan(/^    (\w+) = Data\.define/).flatten
      assert_includes catalogue, %("word.ready" => [WordReady, "WordReadyData"].freeze)
      assert_includes catalogue, %("ping.test" => [PingTest, nil].freeze)
      assert_includes catalogue, %("world.moved" => :world_moved)
      assert_includes catalogue, "def self.parse(json)"

      first = %w[operations.rb streams.rb version.rb events/catalogue.rb].to_h { |f| [f, File.binread(File.join(out, f))] }
      generate(out, version_file)
      first.each { |f, bytes| assert_equal bytes, File.binread(File.join(out, f)), "#{f} differs between runs" }

      Dir.mkdir(File.join(out, "models"))
      File.write(File.join(out, "models", "stream_words_event.rb"), "")
      _, err, status = generate(out, version_file)
      refute status.success?
      assert_includes err, "stream_words_event.rb"

      # 30.9.26aa D3: a model file named after an event arm is refused too.
      File.delete(File.join(out, "models", "stream_words_event.rb"))
      File.write(File.join(out, "models", "word_ready.rb"), "")
      _, err, status = generate(out, version_file)
      refute status.success?
      assert_includes err, "word_ready.rb"
    end
  end
end
