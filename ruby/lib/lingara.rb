# frozen_string_literal: true

# The Lingara API client (https://api.getlingara.com): the standard library
# only. Lingara::Client is the entry point; conformance/CONTRACT.md is the
# behaviour every official library keeps.

require_relative "lingara/version"
require_relative "lingara/errors"
require_relative "lingara/access_token"
require_relative "lingara/retry"
require_relative "lingara/transport"
require_relative "lingara/user_agent"
require_relative "lingara/deprecation"
require_relative "lingara/sse_decoder"
require_relative "lingara/response"

Dir[File.join(__dir__, "lingara/models/*.rb")].sort.each { |file| require file }

require_relative "lingara/streams"
require_relative "lingara/operations"
require_relative "lingara/client_credentials"
require_relative "lingara/event_stream"
require_relative "lingara/client"
